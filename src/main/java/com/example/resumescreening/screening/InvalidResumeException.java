package com.example.resumescreening.screening;

/** Bad resume input: unsupported file, unreadable file, bad folder path, or nothing to screen. */
public class InvalidResumeException extends RuntimeException {

	public InvalidResumeException(String message) {
		super(message);
	}

	public InvalidResumeException(String message, Throwable cause) {
		super(message, cause);
	}

}
