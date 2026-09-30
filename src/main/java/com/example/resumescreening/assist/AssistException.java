package com.example.resumescreening.assist;

/** Claude could not produce a usable suggestion: the API failed, or its answer broke the spec rules twice. */
public class AssistException extends RuntimeException {

	public AssistException(String message) {
		super(message);
	}

	public AssistException(String message, Throwable cause) {
		super(message, cause);
	}

}
