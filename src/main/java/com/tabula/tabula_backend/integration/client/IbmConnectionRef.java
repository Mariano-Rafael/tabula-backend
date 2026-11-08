package com.tabula.tabula_backend.integration.client;

/** Safe to persist inside private job/session bindings. Contains no API key or IAM token. */
public record IbmConnectionRef(String integrationId, String serviceUrl) { }
