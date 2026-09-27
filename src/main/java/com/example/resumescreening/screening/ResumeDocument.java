package com.example.resumescreening.screening;

/** A resume's display name (file name without extension) and extracted text. */
public record ResumeDocument(String name, String text) {
}
