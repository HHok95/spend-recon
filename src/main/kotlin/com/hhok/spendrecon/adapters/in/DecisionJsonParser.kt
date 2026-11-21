package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hhok.spendrecon.application.DecisionCommand
import org.springframework.stereotype.Component

/**
 * Reads the raw JSON body for approve and reject.
 * A 400 here is thrown before the idempotency insert, so a bad body does not consume the key.
 */
@Component
class DecisionJsonParser(
    private val mapper: ObjectMapper,
) {
    fun parseApprove(body: ByteArray): DecisionCommand = parse(body, reasonRequired = false)

    fun parseReject(body: ByteArray): DecisionCommand = parse(body, reasonRequired = true)

    private fun parse(
        body: ByteArray,
        reasonRequired: Boolean,
    ): DecisionCommand {
        val node = objectNode(body)
        rejectUnknown(node)
        val reason = optionalString(node, "reason")
        if (reasonRequired && reason.isNullOrEmpty()) {
            throw InvalidRequestException("Field reason must not be blank.")
        }
        if (reason != null && reason.length > MAX_REASON_LENGTH) {
            throw InvalidRequestException("Field reason must be at most $MAX_REASON_LENGTH characters.")
        }
        return DecisionCommand(
            version = requiredVersion(node),
            actor = requiredActor(node),
            reason = reason?.ifEmpty { null },
        )
    }

    private fun objectNode(body: ByteArray): ObjectNode {
        if (body.isEmpty()) {
            throw InvalidRequestException("Request body must be a JSON object.")
        }
        val node =
            try {
                val parser = mapper.factory.createParser(body)
                val tree: JsonNode = mapper.readTree(parser)
                if (parser.nextToken() != null) {
                    throw InvalidRequestException("Request body must be one JSON value.")
                }
                tree
            } catch (exception: InvalidRequestException) {
                throw exception
            } catch (_: Exception) {
                throw InvalidRequestException("Request body must be a JSON object.")
            }
        if (node !is ObjectNode) {
            throw InvalidRequestException("Request body must be a JSON object.")
        }
        return node
    }

    private fun rejectUnknown(node: ObjectNode) {
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in FIELDS }
        if (unknown != null) {
            throw InvalidRequestException("Unknown field $unknown.")
        }
    }

    private fun requiredVersion(node: ObjectNode): Long {
        if (!node.has("version") || node.get("version").isNull) {
            throw InvalidRequestException("Missing field version.")
        }
        val value = node.get("version")
        if (!value.isNumber || value.isFloatingPointNumber || !value.canConvertToLong()) {
            throw InvalidRequestException("Field version must be an integer.")
        }
        val version = value.longValue()
        if (version < 0) {
            throw InvalidRequestException("Field version must be zero or greater.")
        }
        return version
    }

    private fun requiredActor(node: ObjectNode): String {
        val actor = requiredString(node, "actor")
        if (actor.isEmpty()) {
            throw InvalidRequestException("Field actor must not be blank.")
        }
        if (actor.length > MAX_ACTOR_LENGTH) {
            throw InvalidRequestException("Field actor must be at most $MAX_ACTOR_LENGTH characters.")
        }
        return actor
    }

    private fun requiredString(
        node: ObjectNode,
        field: String,
    ): String {
        if (!node.has(field) || node.get(field).isNull) {
            throw InvalidRequestException("Missing field $field.")
        }
        return textual(node.get(field), field)
    }

    private fun optionalString(
        node: ObjectNode,
        field: String,
    ): String? {
        if (!node.has(field) || node.get(field).isNull) {
            return null
        }
        return textual(node.get(field), field)
    }

    private fun textual(
        value: JsonNode,
        field: String,
    ): String {
        if (!value.isTextual) {
            throw InvalidRequestException("Field $field must be a string.")
        }
        return value.asText().trim()
    }

    companion object {
        private val FIELDS = setOf("version", "actor", "reason")
        const val MAX_ACTOR_LENGTH = 200
        const val MAX_REASON_LENGTH = 2000
    }
}
