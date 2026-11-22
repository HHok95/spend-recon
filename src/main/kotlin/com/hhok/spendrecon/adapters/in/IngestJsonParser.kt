package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.hhok.spendrecon.application.IngestDocumentCommand
import com.hhok.spendrecon.application.IngestTransactionCommand
import com.hhok.spendrecon.domain.Currency
import com.hhok.spendrecon.domain.DocumentKind
import com.hhok.spendrecon.domain.InvalidMoneyException
import com.hhok.spendrecon.domain.Money
import com.hhok.spendrecon.domain.trimDocumentNumber
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads the raw JSON body. Amount and tax must be JSON strings. A JSON number is a 400,
 * because a binary float cannot represent 0.10 exactly.
 */
@Component
class IngestJsonParser(
    private val mapper: ObjectMapper,
) {
    fun parseTransaction(body: ByteArray): IngestTransactionCommand {
        val node = objectNode(body)
        rejectUnknown(node, TRANSACTION_FIELDS)
        val currency = Currency.parse(requiredString(node, "currency"))
        return IngestTransactionCommand(
            cardOwnerId = requiredOwner(node),
            merchantRaw = requiredMerchant(node),
            amount = requiredMoney(node, "amount", currency),
            tax = optionalMoney(node, currency),
            txnDate = requiredDate(node, "txnDate"),
        )
    }

    fun parseDocument(body: ByteArray): IngestDocumentCommand {
        val node = objectNode(body)
        rejectUnknown(node, DOCUMENT_FIELDS)
        val currency = Currency.parse(requiredString(node, "currency"))
        return IngestDocumentCommand(
            kind = requiredKind(node),
            cardOwnerId = requiredOwner(node),
            merchantRaw = requiredMerchant(node),
            amount = requiredMoney(node, "amount", currency),
            tax = optionalMoney(node, currency),
            docDate = requiredDate(node, "docDate"),
            documentNumber = optionalDocumentNumber(node),
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

    private fun rejectUnknown(
        node: ObjectNode,
        allowed: Set<String>,
    ) {
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in allowed }
        if (unknown != null) {
            throw InvalidRequestException("Unknown field $unknown.")
        }
    }

    private fun requiredOwner(node: ObjectNode): String {
        val owner = requiredString(node, "cardOwnerId")
        if (owner.isEmpty()) {
            throw InvalidRequestException("Field cardOwnerId must not be blank.")
        }
        return owner
    }

    private fun requiredMerchant(node: ObjectNode): String {
        val merchant = requiredString(node, "merchant")
        if (merchant.isEmpty()) {
            throw InvalidRequestException("Field merchant must not be blank.")
        }
        return merchant
    }

    private fun requiredKind(node: ObjectNode): DocumentKind {
        val kind = requiredString(node, "kind")
        return try {
            DocumentKind.valueOf(kind)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("Field kind must be RECEIPT or BILL.")
        }
    }

    private fun requiredMoney(
        node: ObjectNode,
        field: String,
        currency: Currency,
    ): Money {
        try {
            return Money.parse(requiredString(node, field), currency)
        } catch (exception: InvalidMoneyException) {
            throw InvalidRequestException(exception.message ?: "Invalid amount.")
        }
    }

    private fun optionalMoney(
        node: ObjectNode,
        currency: Currency,
    ): Money? {
        val text = optionalString(node, "tax") ?: return null
        if (text.isEmpty()) {
            throw InvalidRequestException("Field tax must not be blank.")
        }
        try {
            return Money.parse(text, currency)
        } catch (exception: InvalidMoneyException) {
            throw InvalidRequestException(exception.message ?: "Invalid amount.")
        }
    }

    private fun requiredDate(
        node: ObjectNode,
        field: String,
    ): LocalDate {
        val text = requiredString(node, field)
        try {
            return LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            throw InvalidRequestException("Field $field must be an ISO-8601 date.")
        }
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

    /**
     * Trim Unicode whitespace, including tab and NBSP. A blank or whitespace-only
     * value is absent, so it is not part of the exact duplicate key.
     */
    private fun optionalDocumentNumber(node: ObjectNode): String? =
        trimDocumentNumber(optionalString(node, "documentNumber"))

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
        private val TRANSACTION_FIELDS =
            setOf("cardOwnerId", "merchant", "amount", "currency", "txnDate", "tax")
        private val DOCUMENT_FIELDS =
            setOf("kind", "cardOwnerId", "merchant", "amount", "currency", "docDate", "tax", "documentNumber")
    }
}
