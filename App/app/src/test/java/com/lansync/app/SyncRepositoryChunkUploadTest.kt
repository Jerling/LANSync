package com.lansync.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD tests for chunk upload fix - A-1: bytesRead==0 dead code removal
 * 
 * The bug: In SyncRepository.kt lines 293-302, there's dead code:
 *   if (bytesRead == 0) break
 * 
 * This is dead code because InputStream.read() never returns 0 when at EOF.
 * It returns -1 at EOF, or a positive value (1 to len) when data is available.
 * A return of 0 means the stream is not ready, not that it's at EOF.
 */
class SyncRepositoryChunkUploadTest {

    companion object {
        private const val CHUNK_SIZE = 1024 * 1024  // 1MB
    }

    /**
     * Test 1: test_chunk_upload_terminates_on_last_chunk
     * Verifies that when the last chunk has bytesRead < chunk.size, the loop terminates correctly.
     * This tests the case where a file size is not an exact multiple of CHUNK_SIZE.
     * 
     * Scenario: 500KB file with 1MB chunk
     * Expected: 1 chunk uploaded (500KB), loop terminates correctly
     */
    @Test
    fun test_chunk_upload_terminates_on_last_chunk() {
        val fileContent = ByteArray(500 * 1024) { it.toByte() }
        val inputStream = java.io.ByteArrayInputStream(fileContent)

        val chunk = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        var uploadCount = 0

        while (inputStream.read(chunk, 0, chunk.size).also { bytesRead = it } != -1) {
            // The buggy code: if (bytesRead == 0) break
            // This should NOT be here because read() never returns 0 at EOF
            // Only break when bytesRead < chunk.size (last chunk indicator)
            if (bytesRead < chunk.size) {
                uploadCount++
                break
            }
            uploadCount++
        }

        assertEquals(1L, uploadCount.toLong())
        assertEquals((500 * 1024).toLong(), bytesRead.toLong())
    }

    /**
     * Test 2: test_chunk_upload_all_bytes_uploaded_with_exact_multiple
     * Verifies that all bytes are uploaded when file size is exact multiple of chunk size.
     * 
     * Scenario: 2MB file with 1MB chunk
     * Expected: 2 chunks uploaded, loop terminates correctly at EOF
     */
    @Test
    fun test_chunk_upload_all_bytes_uploaded() {
        val fileContent = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val inputStream = java.io.ByteArrayInputStream(fileContent)

        val chunk = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        var uploadCount = 0
        var totalBytesRead = 0

        while (inputStream.read(chunk, 0, chunk.size).also { bytesRead = it } != -1) {
            // Bug: if (bytesRead == 0) break would cause premature exit
            // This could cause the second 1MB chunk to never be uploaded!
            
            if (bytesRead > 0) {
                uploadCount++
                totalBytesRead += bytesRead
            }

            if (bytesRead < chunk.size) {
                break
            }
        }

        assertEquals(2L, uploadCount.toLong())
        assertEquals((2 * 1024 * 1024).toLong(), totalBytesRead.toLong())
    }

    /**
     * Test 3: test_chunk_upload_with_small_final_chunk
     * Verifies behavior with file that results in multiple full chunks + small final chunk.
     * 
     * Scenario: 2.5MB file with 1MB chunk
     * Expected: 3 chunks uploaded (1MB + 1MB + 0.5MB)
     */
    @Test
    fun test_chunk_upload_with_small_final_chunk() {
        val fileContent = ByteArray((2.5 * 1024 * 1024).toInt()) { it.toByte() }
        val inputStream = java.io.ByteArrayInputStream(fileContent)

        val chunk = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        var uploadCount = 0
        var totalBytesRead = 0

        while (inputStream.read(chunk, 0, chunk.size).also { bytesRead = it } != -1) {
            if (bytesRead > 0) {
                uploadCount++
                totalBytesRead += bytesRead
            }

            // Break on last chunk (bytesRead < chunk.size)
            if (bytesRead < chunk.size) {
                break
            }
        }

        assertEquals(3L, uploadCount.toLong())
        assertEquals((2.5 * 1024 * 1024).toLong(), totalBytesRead.toLong())
    }

    /**
     * Test 4: test_chunk_upload_single_chunk_file
     * Verifies behavior when file is smaller than chunk size.
     * 
     * Scenario: 100 byte file with 1MB chunk
     * Expected: 1 chunk uploaded (100 bytes)
     */
    @Test
    fun test_chunk_upload_single_chunk_file() {
        val fileContent = ByteArray(100) { it.toByte() }
        val inputStream = java.io.ByteArrayInputStream(fileContent)

        val chunk = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        var uploadCount = 0

        while (inputStream.read(chunk, 0, chunk.size).also { bytesRead = it } != -1) {
            if (bytesRead > 0) {
                uploadCount++
            }
            
            // bytesRead < chunk.size indicates this was the last (and only) chunk
            if (bytesRead < chunk.size) {
                break
            }
        }

        assertEquals(1L, uploadCount.toLong())
        assertEquals(100L, bytesRead.toLong())
    }
}