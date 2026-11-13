package com.outlierradar.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-Schema definitions for the Coarena (OpenAI-compatible) structured
 * output mode. They mirror the Firebase [com.google.firebase.ai.type.Schema]
 * definitions used on the Firebase path so both providers return the same
 * shapes and the existing org.json parsers keep working unchanged.
 */
object CoarenaSchemas {

  val analysis: JSONObject = obj(
    "whyViral" to arr(str("3 points explaining why this Short went viral: hook, topic, emotion/curiosity")),
    "hookIdeas" to arr(str("3 punchy opening lines for a similar viral Short")),
    "titleIdeas" to arr(str("3 high-CTR Short titles, max 60 characters each")),
    "contentIdea" to str("One similar Short idea with a 3-step outline"),
    "thumbnailAnalysis" to str("Thumbnail analysis: visible text, dominant colors, face/emotion, curiosity element"),
    "targetAudience" to str("Primary target audience for this Short"),
    "replicabilityScore" to integer("How easy it is to replicate this Short, from 1 to 10"),
    "replicabilityReason" to str("Brief reason for the replicability score"),
    "scriptOutline" to obj(
      "hook0to3s" to str("Hook for first 0-3 seconds"),
      "body" to str("Main body/content flow"),
      "payoffLoop" to str("Payoff or loop ending to drive rewatches")
    ),
    "bestPostingTip" to str("Best posting tip for this kind of Short")
  )

  val trend: JSONObject = obj(
    "patterns" to obj(
      "titleFormulas" to arr(str("Common title formulas/patterns")),
      "topics" to arr(str("Common topics/themes")),
      "hookStyles" to arr(str("Common hook styles")),
      "durations" to arr(str("Common duration/length patterns"))
    ),
    "ideas" to arr(
      obj(
        "title" to str("Ready-to-shoot idea title"),
        "hook" to str("Opening hook for 0-3 seconds"),
        "concept" to str("Core concept and body plan"),
        "whyItCanWork" to str("Why this idea can work")
      )
    )
  )

  val niche: JSONObject = obj(
    "keywords" to arr(
      str("Exactly 6 micro-niche YouTube Shorts search keywords (Hindi, English and Hinglish)")
    )
  )

  val contentPack: JSONObject = obj(
    "hookBreakdown" to str("Why the first 0-3s hook works"),
    "bodyBreakdown" to str("Body/content flow breakdown"),
    "payoffBreakdown" to str("Payoff/loop ending breakdown"),
    "pacingNotes" to str("Pacing and retention notes"),
    "emotionTriggers" to arr(str("Emotion/curiosity triggers")),
    "retentionDrivers" to arr(str("Retention drivers")),
    "filmableConcepts" to arr(str("3 ready-to-film concepts")),
    "titleIdeas" to arr(str("5 high-CTR title ideas")),
    "hookIdeas" to arr(str("5 hook lines for 0-3s")),
    "shotList" to arr(
      obj(
        "shot" to str("Shot description"),
        "visual" to str("Visual/camera direction"),
        "audio" to str("Audio/voiceover direction")
      )
    ),
    "bRollIdeas" to arr(str("B-roll ideas")),
    "postingTime" to str("Best posting time/day"),
    "hashtags" to arr(str("Relevant hashtags"))
  )

  val contentGaps: JSONObject = obj(
    "summary" to str("One-line summary of the content gap opportunity"),
    "gaps" to arr(
      obj(
        "topic" to str("Underserved topic/format"),
        "whyGap" to str("Why it is a gap"),
        "suggestedIdea" to str("Suggested Short idea"),
        "estimatedDemand" to str("Demand level: high/medium/low")
      )
    )
  )

  private fun str(description: String): JSONObject = JSONObject()
    .put("type", "string")
    .put("description", description)

  private fun integer(description: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("description", description)

  private fun arr(items: JSONObject): JSONObject = JSONObject()
    .put("type", "array")
    .put("items", items)

  private fun obj(vararg properties: Pair<String, JSONObject>): JSONObject {
    val props = JSONObject()
    val required = JSONArray()
    properties.forEach { (name, schema) ->
      props.put(name, schema)
      required.put(name)
    }
    return JSONObject()
      .put("type", "object")
      .put("properties", props)
      .put("required", required)
      .put("additionalProperties", false)
  }
}
