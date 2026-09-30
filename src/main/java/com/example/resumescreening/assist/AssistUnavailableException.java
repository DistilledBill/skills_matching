package com.example.resumescreening.assist;

/** Suggestions are turned off because no Claude API key is set. */
public class AssistUnavailableException extends RuntimeException {

	public AssistUnavailableException() {
		super("ANTHROPIC_API_KEY is not set; spec suggestions are unavailable (the editor still works)");
	}

}
