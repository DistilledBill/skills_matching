package com.example.resumescreening.screening;

import java.util.regex.Pattern;

/** Strips contact details that carry no job-related signal before a resume is sent. */
public final class Redactor {

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

	private static final Pattern PROFILE_LINK = Pattern
		.compile("(?:https?://)?(?:www\\.)?(?:github|linkedin)\\.com/\\S+");

	private static final Pattern PHONE = Pattern
		.compile("(?:\\+\\d{1,2}[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");

	private Redactor() {
	}

	public static String redact(String text) {
		text = EMAIL.matcher(text).replaceAll("[email]");
		text = PROFILE_LINK.matcher(text).replaceAll("[link]");
		return PHONE.matcher(text).replaceAll("[phone]");
	}

}
