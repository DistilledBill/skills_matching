package com.example.resumescreening.screening;

/**
 * One resume file in a folder.
 *
 * @param name   display name used in results (file name without extension)
 * @param format {@code pdf}, {@code txt} or {@code md}
 * @param size   bytes on disk
 */
public record ResumeFile(String fileName, String name, String format, long size) {
}
