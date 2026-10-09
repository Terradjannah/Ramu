package com.assistant.adi.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.io.File

class CoreRegressionTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun oversizedDownloadIsNotComplete() {
        assertFalse(DownloadValidation.complete(101,100)); assertFalse(DownloadValidation.complete(50,100))
        assertFalse(DownloadValidation.complete(0,0)); assertTrue(DownloadValidation.complete(100,100))
    }
    @Test fun rejectsInvalidContentRange() {
        assertNull(DownloadValidation.range("bytes */100"))
        assertNull(DownloadValidation.range("bytes 10-100/100"))
        assertNull(DownloadValidation.range("bytes 90-10/100"))
        assertEquals(DownloadValidation.Range(10,99,100),DownloadValidation.range("bytes 10-99/100"))
    }
    @Test fun partialCounterDoesNotMakeModelReady() {
        val file=temporary.newFile("model.litertlm"); file.writeText("partial")
        assertFalse(ModelFiles.isReady(file))
        File(file.path+".complete").writeText("100")
        assertFalse(ModelFiles.isReady(file))
        ModelFiles.markReady(file); assertTrue(ModelFiles.isReady(file))
        file.appendText("corrupt"); assertFalse(ModelFiles.isReady(file))
    }
    @Test fun throughputUsesElapsedTimeAndHandlesReset() {
        assertEquals(1.0,SampleMath.kilobytesPerSecond(0,2048,2000),0.001)
        assertEquals(0.0,SampleMath.kilobytesPerSecond(-1,-1,1000),0.0)
        assertEquals(0.0,SampleMath.kilobytesPerSecond(100,0,1000),0.0)
    }
    @Test fun splitUsageAcrossHours() {
        val zone=ZoneId.of("Asia/Jakarta")
        val start=ZonedDateTime.of(2026,9,8,10,50,0,0,zone).toInstant().toEpochMilli()
        val buckets=LongArray(24); HourBuckets.add(buckets,start,start+20*60_000,zone)
        assertEquals(600_000L,buckets[10]); assertEquals(600_000L,buckets[11]); assertEquals(1_200_000L,buckets.sum())
    }
    @Test fun splitUsageAcrossMidnight() {
        val zone=ZoneId.of("Asia/Jakarta")
        val start=ZonedDateTime.of(2026,9,8,23,50,0,0,zone).toInstant().toEpochMilli()
        val buckets=LongArray(24); HourBuckets.add(buckets,start,start+20*60_000,zone)
        assertEquals(600_000L,buckets[23]); assertEquals(600_000L,buckets[0])
    }
    @Test fun splitUsageAcrossDstKeepsAbsoluteDurationInLocalBuckets() {
        val zone=ZoneId.of("America/New_York")
        val start=ZonedDateTime.of(2026,11,1,1,50,0,0,zone).toInstant().toEpochMilli()
        val parts=mutableListOf<Long>()
        HourBuckets.split(start,start+20*60_000,zone) { from,to,_ -> parts += to-from }
        assertEquals(1_200_000L,parts.sum())
        assertTrue(parts.all { it > 0 })
    }
    @Test fun privacyDefaultNeverStoresContent() {
        val result=NotificationPrivacy.sanitize("sms","Rahasia","123-456",false)
        assertFalse(result.toString().contains("123")); assertEquals("Konten tidak disimpan",result.second)
    }
    @Test fun masksFormattedAndAlphanumericOtp() {
        for(content in listOf("OTP: 123-456","kode AB12CD","PIN 1234","verification code 12 34 56")) {
            assertEquals("[KONTEN_SENSITIF_DIBLOKIR]",NotificationPrivacy.sanitize("sms","",content,true).second)
        }
    }
    @Test fun masksNumbersWithoutKeyword() {
        assertEquals("Nomor [REDACTED]",NotificationPrivacy.sanitize("sms","","Nomor 123-456",true).second)
    }
    @Test fun blocksBankingPackageContent() {
        assertEquals("[KONTEN_SENSITIF_DIBLOKIR]",NotificationPrivacy.sanitize("com.bank.mandiri","Saldo","Rp 100",true).second)
    }
}
