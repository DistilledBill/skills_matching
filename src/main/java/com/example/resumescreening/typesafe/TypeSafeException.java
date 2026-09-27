package com.example.resumescreening.typesafe;

/** The TypeSafe API failed, returned an error status, or returned an unexpected answer. */
public class TypeSafeException extends RuntimeException {

	private final Integer status;

	public TypeSafeException(String message) {
		this(message, null, null);
	}

	public TypeSafeException(String message, Integer status, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	/** Upstream HTTP status, or null when no response was received. */
	public Integer status() {
		return status;
	}

}
