package org.zalando.zally.ruleset.zalando

import com.typesafe.config.ConfigFactory
import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.zalando.zally.core.DefaultContext
import org.zalando.zally.core.ObjectTreeReader
import org.zalando.zally.core.rulesConfig
import org.zalando.zally.ruleset.zalando.util.getResourceJson

class UseOpenApiRuleTest {

    private val rule = UseOpenApiRule(rulesConfig)

    @Test
    fun shouldNotFailOnCorrectYaml() {
        listOf("swagger2_petstore_expanded.yaml", "openapi3_petstore.yaml").forEach {
            val json = getResourceJson(it)
            val validations = rule.validateSchema(json)
            assertThat(validations).hasSize(0)
        }
    }

    @Test
    fun shouldNotFailOnCorrectJson() {
        listOf("swagger2_petstore_expanded.json", "openapi3_petstore.json").forEach {
            val json = getResourceJson(it)
            val validations = rule.validateSchema(json)
            assertThat(validations).hasSize(0)
        }
    }

    @Test
    fun shouldReportInvalidYaml() {
        val json = ObjectTreeReader().read("foo: bar")
        val validations = rule.validateSchema(json)
        assertThat(validations).isNotEmpty
        assertThat(validations).anyMatch { it.description.matches(Regex(".*Object has missing required properties.*")) }
        assertThat(validations).anyMatch { it.description.matches(Regex(".*Object instance has properties.*")) }
    }

    @Test
    fun shouldLoadSchemaFromResourceIfUrlNotSpecified() {
        val config = ConfigFactory.parseString(
            """
        UseOpenApiRule {
             // swagger_schema_url not defined
        }
        """
        )

        val json = ObjectTreeReader().read("foo: bar")
        val validations = UseOpenApiRule(config).validateSchema(json)
        assertThat(validations).isNotEmpty
    }

    @Test
    fun shouldLoadSchemaFromResourceIfLoadFromUrlFailed() {
        val config = ConfigFactory.parseString(
            """
        UseOpenApiRule {
             swagger_schema_url: "http://localhost/random_url.html"
        }
        """
        )

        val json = ObjectTreeReader().read("foo: bar")
        val validations = UseOpenApiRule(config).validateSchema(json)
        assertThat(validations).isNotEmpty
    }

    @Test
    fun `checkIfTheFormatIsYaml should return a violation if JSON is used`() {
        val context = DefaultContext("\t\r\n{\"openapi\": \"3.0.1\"}\t\r\n", OpenAPI(), null)

        val violation = rule.checkIfTheFormatIsYAML(context)

        assertThat(violation).isNotNull
        assertThat(violation!!.description).containsPattern(".*must use YAML format.*")
    }

    @Test
    fun `checkIfTheFormatIsYaml should return no violation if YAML is used`() {
        val context = DefaultContext("openapi: 3.0.1", OpenAPI(), null)

        val violation = rule.checkIfTheFormatIsYAML(context)

        assertThat(violation).isNull()
    }

    @Test
    fun `validateSchema should return no violation for valid OpenAPI 3 specification`() {
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.0.1
            info:
              title: "Minimal API"
              version: "1.0.0"
            paths: {}
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `validateSchema should return no violation for valid OpenAPI 3 specification with uri-reference`() {
        @Language("YAML")
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.0.0
            info:
              title: Example API with contact url
              version: 1.0.0
              contact:
                url: http://example.com
            paths:
              /path:
                post:
                  responses:
                    '200':
                      description: Example response
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `validateSchema should return no violation for OpenAPI 3_1 reference with description`() {
        @Language("YAML")
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.1.0
            info:
              title: Example API
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Metric:
                  type: object
                ProductSimpleAttributes:
                  type: object
                  description: Contains the product attributes of a size
                  required:
                    - gtin
                  properties:
                    gtin:
                      type: string
                    metric.cable_length:
                      ${'$'}ref: '#/components/schemas/Metric'
                      description: The length of the article's cable.
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `validateSchema should return no violation for OpenAPI 3_1 specific features`() {
        @Language("YAML")
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.1.0
            jsonSchemaDialect: https://spec.openapis.org/oas/3.1/dialect/base
            info:
              title: Example API
              summary: An API using OpenAPI 3.1 features
              version: 1.0.0
              license:
                name: MIT
                identifier: MIT
            webhooks:
              newPet:
                post:
                  requestBody:
                    description: Information about a new pet
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Pet'
                  responses:
                    '200':
                      description: Return a 200 status
            components:
              pathItems:
                pets:
                  get:
                    summary: List pets
              responses:
                NotFound:
                  ${'$'}ref: '#/components/responses/Problem'
                  description: Resource not found
                Problem:
                  description: A problem occurred
              schemas:
                Pet:
                  type: object
                  required: [name]
                  properties:
                    name:
                      type: [string, 'null']
                      examples: [Rex]
                    age:
                      type: integer
                      exclusiveMinimum: 0
                    kind:
                      const: dog
                    tags:
                      type: array
                      prefixItems:
                        - type: string
                  additionalProperties: false
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `validateSchema should report violations for invalid OpenAPI 3_1 specification`() {
        @Language("YAML")
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.1.0
            info:
              version: 1.0.0
            paths:
              /pets:
                get:
                  parameters:
                    - name: limit
                      schema:
                        type: integer
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).isNotEmpty
        assertThat(violations).allMatch { it.description.startsWith("Does not match openapi31 schema") }
        assertThat(violations).anyMatch { it.pointer.toString() == "/info" }
    }

    @Test
    fun `validateSchema should still report OpenAPI 3_0 reference with description`() {
        @Language("YAML")
        val jsonNode = ObjectTreeReader().read(
            """
            openapi: 3.0.3
            info:
              title: Example API
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Metric:
                  type: object
                Product:
                  type: object
                  properties:
                    metric:
                      ${'$'}ref: '#/components/schemas/Metric'
                      description: The metric.
            """.trimIndent()
        )

        val violations = rule.validateSchema(jsonNode)

        assertThat(violations).anyMatch { it.description.startsWith("Does not match openapi3 schema") }
    }
}
