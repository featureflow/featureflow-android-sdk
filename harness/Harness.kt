package io.featureflow.android

import java.util.Date

/**
 * Interactive harness for exercising the SDK against a real Featureflow environment, without the
 * Android toolchain.
 *
 *     FEATUREFLOW_CLIENT_KEY=sdk-js-env-xxxx ./harness/run.sh
 *
 * Drives the platform-independent core directly: request shape, base64url path encoding, response
 * parsing, rule walking, client-side time resolution and the outgoing event batch. Caching,
 * lifecycle and polling need the Android framework — see `../example`.
 */

private const val BOLD = "[1m"
private const val RESET = "[0m"

private fun heading(text: String) = println("\n$BOLD$text$RESET")

private fun printFeatures(features: Map<String, String>) {
    if (features.isEmpty()) {
        println("  (no features — check the key is for the right environment, and that the")
        println("   project has features with 'in client API' enabled)")
        return
    }
    val width = features.keys.maxOf { it.length }
    features.keys.sorted().forEach { key ->
        val variant = features[key] ?: "off"
        val marker = if (variant == "off") "○" else "●"
        println("  $marker ${key.padEnd(width)}  $variant")
    }
}

private fun describe(value: JsonValue?): String = when (value) {
    null -> "(none)"
    else -> value.toJson().toString()
}

private fun usage(): Nothing {
    System.err.println(
        """
        Featureflow Android SDK harness (JVM — no Android toolchain required)

        Set a client SDK key (Environments → your environment → API Keys; it starts with
        sdk-js-env-):

            FEATUREFLOW_CLIENT_KEY=sdk-js-env-xxxx ./harness/run.sh

        Options:
            --key <key>          client SDK key, instead of the environment variable
            --user <id>          user id to evaluate for (default: harness-user)
            --base-url <url>     override the evaluate host (self-hosted or staging)
            --events-url <url>   override the events host
            --once               print the evaluation once and exit, for scripting and CI

        Environment variables: FEATUREFLOW_CLIENT_KEY, FEATUREFLOW_BASE_URL,
        FEATUREFLOW_EVENTS_URL.
        """.trimIndent()
    )
    kotlin.system.exitProcess(2)
}

fun main(args: Array<String>) {
    fun option(name: String): String? {
        val index = args.indexOf("--$name")
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    val env = System.getenv()
    val apiKey = option("key") ?: env["FEATUREFLOW_CLIENT_KEY"] ?: usage()

    if (!apiKey.startsWith("sdk-js-env-")) {
        // A server key here is not just the wrong endpoint — it means a secret key, which
        // downloads the whole ruleset, is being handled as though it were public.
        System.err.println(
            """
            ⚠️  That key does not start with sdk-js-env-.

                This SDK is a client SDK and needs the *client* key. A server key
                (sdk-srv-env-) is secret and must never be shipped in an app.

                Continuing anyway, but expect a 401.

            """.trimIndent()
        )
    }

    val config = FeatureflowConfig(
        baseUrl = option("base-url") ?: env["FEATUREFLOW_BASE_URL"] ?: "https://app.featureflow.io",
        eventsUrl = option("events-url") ?: env["FEATUREFLOW_EVENTS_URL"]
            ?: "https://events.featureflow.io"
    )

    var user = FeatureflowUser(option("user") ?: "harness-user")
    val rest = RestClient(apiKey, config)

    heading("Connecting")
    println("  key         ${apiKey.take(18)}…")
    println("  evaluate    ${config.baseUrl}")
    println("  events      ${config.eventsUrl}")
    println("  user        ${user.id}")

    var controls: Map<String, EvaluatedControl> = emptyMap()

    fun fetch(): Boolean {
        val started = System.currentTimeMillis()
        return try {
            controls = rest.evaluate(user)
            println("  fetched     ${controls.size} controls in ${System.currentTimeMillis() - started}ms")
            true
        } catch (e: Exception) {
            println("  FAILED      ${e.message}")
            false
        }
    }

    fun features(): Map<String, String> = controls.mapValues { (_, control) ->
        // Resolved against the clock now, so a time-gated rule answers for this moment.
        RuleEvaluator.evaluate(control)?.variant?.lowercase() ?: "off"
    }

    if (!fetch()) kotlin.system.exitProcess(1)

    heading("Features")
    printFeatures(features())

    if (args.contains("--once")) return

    heading("Commands")
    println(
        """
          list                  every feature and its variant
          eval <key>            evaluate one feature and show the rules behind it
          json <key>            the variant's JSON config payload, if it has one
          track <goal> [value]  send a goal event immediately
          impression <key>      send a summarised impression event for a feature
          user <id>             switch user and re-fetch
          refresh               fetch again
          raw <key>             the server's raw rules for one feature
          quit
        """.trimIndent()
    )

    while (true) {
        print("\n> ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue
        val parts = line.split(" ")
        val argument = parts.getOrNull(1)

        when (parts[0]) {
            "list" -> printFeatures(features())

            "eval" -> {
                if (argument == null) { println("  usage: eval <key>"); continue }
                val control = controls[argument]
                if (control == null) {
                    println("  $argument = off  (no control in the response — unknown feature key?)")
                    continue
                }
                val resolved = RuleEvaluator.evaluate(control)
                val evaluation = Evaluation(resolved?.variant ?: "off", resolved?.value)
                println("  $argument = ${evaluation.value()}")
                println("    isOn ${evaluation.isOn()}  isOff ${evaluation.isOff()}")
                println("    rules returned: ${control.rules.size}")
                control.rules.forEachIndexed { index, rule ->
                    val kind = if (rule.audience == null) "fully evaluated" else
                        "partial (${rule.audience.conditions.size} time condition(s) resolved here)"
                    println("      [$index] ${rule.variant} — $kind")
                }
                if (resolved?.value != null) println("    json ${describe(resolved.value)}")
            }

            "json" -> {
                if (argument == null) { println("  usage: json <key>"); continue }
                println("  " + describe(RuleEvaluator.evaluate(controls[argument] ?: EvaluatedControl(emptyList()))?.value))
            }

            "raw" -> {
                if (argument == null) { println("  usage: raw <key>"); continue }
                println("  " + (controls[argument]?.toJson()?.toString(2) ?: "(no control)"))
            }

            "track" -> {
                if (argument == null) { println("  usage: track <goal> [value]"); continue }
                val value = parts.getOrNull(2)?.toDoubleOrNull()
                val event = SdkEvent.Goal(argument, user, value, null, Iso8601.format(Date()))
                try {
                    rest.postEvents(listOf(event))
                    println("  sent goal '$argument'${value?.let { " value $it" } ?: ""}")
                } catch (e: Exception) {
                    println("  failed: ${e.message}")
                }
            }

            "impression" -> {
                if (argument == null) { println("  usage: impression <key>"); continue }
                val variant = features()[argument] ?: "off"
                val event = SdkEvent.Evaluation(argument, variant, 1, user, Iso8601.format(Date()))
                try {
                    rest.postEvents(listOf(event))
                    println("  sent impression for '$argument' = $variant")
                } catch (e: Exception) {
                    println("  failed: ${e.message}")
                }
            }

            "user" -> {
                if (argument == null) { println("  usage: user <id>"); continue }
                user = FeatureflowUser(argument)
                println("  switched to ${user.id}")
                if (fetch()) printFeatures(features())
            }

            "refresh" -> if (fetch()) printFeatures(features())

            "quit", "exit" -> return

            else -> println("  unknown command '${parts[0]}' — try list, eval, json, raw, track, impression, user, refresh, quit")
        }
    }
}
