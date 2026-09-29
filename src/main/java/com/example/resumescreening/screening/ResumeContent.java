package com.example.resumescreening.screening;

/**
 * One resume's text as stored, contact details included; PDFs are extracted to text.
 *
 * @param fileName the file name with its extension
 * @param name     the file name without its extension, as screening reports it
 * @param format   pdf, txt, or md
 */
public record ResumeContent(String fileName, String name, String format, String text) {

	/** The same resume with contact details removed, as Jev receives it. */
	public ResumeContent redacted() {
		return new ResumeContent(fileName, name, format, Redactor.redact(text));
	}

}
