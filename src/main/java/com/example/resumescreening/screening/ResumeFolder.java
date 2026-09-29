package com.example.resumescreening.screening;

/** A folder directly under the resumes root and how many supported resumes it holds. */
public record ResumeFolder(String path, int fileCount) {
}
