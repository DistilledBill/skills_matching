package com.example.resumescreening.typesafe;

import java.util.Map;

/**
 * Body of {@code POST /v1/systemone}. Question ids are for code only and are
 * not sent to the model.
 */
public record SystemOneRequest(Object state, String model, Map<String, Question> questions) {
}
