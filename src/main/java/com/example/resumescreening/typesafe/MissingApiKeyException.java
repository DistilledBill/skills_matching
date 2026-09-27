package com.example.resumescreening.typesafe;

public class MissingApiKeyException extends RuntimeException {

	public MissingApiKeyException() {
		super("TYPESAFE_API_KEY is not set; screening is unavailable (job listing and preview still work)");
	}

}
