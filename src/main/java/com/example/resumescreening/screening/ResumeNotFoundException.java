package com.example.resumescreening.screening;

/** A resume file that is not in the requested folder. */
public class ResumeNotFoundException extends RuntimeException {

	public ResumeNotFoundException(String message) {
		super(message);
	}

}
