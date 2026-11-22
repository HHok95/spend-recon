package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/** RFC 7807 problem+json with the four fields this API returns. */
fun problemJson(
    json: ObjectMapper,
    status: HttpStatus,
    detail: String,
    type: String = "about:blank",
    title: String = status.reasonPhrase,
): String =
    json.writeValueAsString(
        linkedMapOf(
            "type" to type,
            "title" to title,
            "status" to status.value(),
            "detail" to detail,
        ),
    )

fun problemResponse(
    json: ObjectMapper,
    status: HttpStatus,
    detail: String,
    type: String = "about:blank",
    title: String = status.reasonPhrase,
): ResponseEntity<String> =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemJson(json, status, detail, type, title))
