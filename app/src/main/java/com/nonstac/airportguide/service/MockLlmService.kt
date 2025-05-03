package com.nonstac.airportguide.service

import com.nonstac.airportguide.data.model.LLMResponse
import com.nonstac.airportguide.data.model.MapStateInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.abs

// Service to process user input, with fuzzy matching and intent detection.
// NOTE: Compound queries (location + navigation in one sentence) are intentionally NOT supported.
// Handles specific location/bathroom/exit requests first, falls back to nearest if specific parse fails.
// Uses a stricter fuzzy match distance (1) and a cleaned list of known locations based on the provided map.
class MockLlmService {

    // --- Keyword Sets, Maps, Regex ---
    private val locationUpdateTriggers = setOf(
        "i am at", "i'm at", "my location is", "currently at",
        "located at", "presently at", "find me at", "here at",
        "i am near", "i'm near", "i am next to", "i'm next to"
    )
    private val navigationTriggers = setOf(
        "how do i get to", "how do you get to",
        "take me to", "go to", "where is", "directions to", "path to",
        "which way to", "navigate to", "show me", "guide me to", "lead me to"
    )
    private val distanceTriggers = setOf(
        "how far", "distance left", "how long", "time left",
        "remaining distance", "walk time", "eta", "estimated time"
    )
    private val lostTriggers = setOf(
        "i am lost", "i'm lost", "where am i", "confused",
        "i don't know where i am", "i do not know where i am",
        "help me find my location"
    )
    private val confusedNavigationTriggers = setOf(
        "i don't know where to go", "i do not know where to go",
        "what should i do now", "where should i go now",
        "confused about where to go", "what's next", "where to next"
    )
    // General keywords used for FALLBACK "nearest" detection ONLY if specific parsing fails
    private val bathroomKeywords = setOf("bathroom", "restroom", "toilet", "wc", "washroom", "lavatory", "loo")
    private val exitKeywords = setOf("exit", "emergency exit", "way out", "emergency door")

    // *** MODIFIED knownLocations: Cleaned to match only JSON map elements ***
    // Aliases map to canonical names from the JSON 'name' field.
    // Keys here should generally use words (like "second") as input is pre-processed before matching.
    private val knownLocations: Map<String, String> = mapOf(
        // Base type for numbered location PRESENT in JSON
        "security checkpoint" to "Security Checkpoint", // Base for "Security Checkpoint 1"

        // Specific Locations from JSON map
        "entrance" to "Main Entrance",
        "main entrance" to "Main Entrance",

        "check-in area a" to "Check-in Area A", // Specific area from JSON
        "check in area a" to "Check-in Area A",
        "checkin area a" to "Check-in Area A",
        "area a check in" to "Check-in Area A",
        "area a" to "Check-in Area A", // Might be ambiguous, test usage

        "security checkpoint 1" to "Security Checkpoint 1", // Specific numbered location
        "security 1" to "Security Checkpoint 1",
        "checkpoint 1" to "Security Checkpoint 1",

        "duty free" to "Duty Free Shop", // Specific shop
        "dutyfree" to "Duty Free Shop",
        "duty-free" to "Duty Free Shop",
        "duty free shop" to "Duty Free Shop",
        "dutyfree shop" to "Duty Free Shop",
        "duty-free shop" to "Duty Free Shop",

        "lounge" to "VIP Lounge", // Specific lounge
        "vip lounge" to "VIP Lounge",

        "stairs/elevator to floor 2" to "Stairs/Elevator to Floor 2", // Specific connection point
        "stairs to floor 2" to "Stairs/Elevator to Floor 2",
        "elevator to floor 2" to "Stairs/Elevator to Floor 2",
        "stairs up" to "Stairs/Elevator to Floor 2",

        "stairs/elevator from floor 1" to "Stairs/Elevator from Floor 1", // Specific connection point
        "stairs from floor 1" to "Stairs/Elevator from Floor 1",
        "elevator from floor 1" to "Stairs/Elevator from Floor 1",

        // Specific Bathrooms (Aliases use words - input is pre-processed to digits before lookup)
        "restrooms near duty free" to "Restrooms near Duty Free", // B1
        "bathroom near duty free" to "Restrooms near Duty Free",
        "bathroom 1" to "Restrooms near Duty Free", // Matches pre-processed "1 bathroom" or "bathroom 1"
        "restroom 1" to "Restrooms near Duty Free",
        "first bathroom" to "Restrooms near Duty Free", // Matches pre-processed "1 bathroom"
        "1st bathroom" to "Restrooms near Duty Free", // Matches pre-processed "1 bathroom"

        "restrooms near gates b" to "Restrooms near Gates B", // B2
        "bathroom near gates b" to "Restrooms near Gates B",
        "restrooms near gate b" to "Restrooms near Gates B",
        "bathroom near gate b" to "Restrooms near Gates B",
        "bathroom 2" to "Restrooms near Gates B", // Matches pre-processed "2 bathroom" or "bathroom 2"
        "restroom 2" to "Restrooms near Gates B",
        "second bathroom" to "Restrooms near Gates B", // Matches pre-processed "2 bathroom"
        "2nd bathroom" to "Restrooms near Gates B", // Matches pre-processed "2 bathroom"

        "restrooms floor 2" to "Restrooms Floor 2", // B3
        "bathroom floor 2" to "Restrooms Floor 2",
        "restroom floor 2" to "Restrooms Floor 2",
        "bathroom on floor 2" to "Restrooms Floor 2",
        "restroom on floor 2" to "Restrooms Floor 2",
        "bathroom on the second floor" to "Restrooms Floor 2", // Matches pre-processed "bathroom on the 2 floor"
        "restroom on the second floor" to "Restrooms Floor 2",
        "second floor bathroom" to "Restrooms Floor 2",         // Matches pre-processed "2 floor bathroom"
        "second floor restroom" to "Restrooms Floor 2",
        "bathroom 3" to "Restrooms Floor 2", // Matches pre-processed "3 bathroom" or "bathroom 3"
        "restroom 3" to "Restrooms Floor 2",
        "third bathroom" to "Restrooms Floor 2",             // Matches pre-processed "3 bathroom"
        "3rd bathroom" to "Restrooms Floor 2",

        // Specific Exits (Aliases use words - input is pre-processed to digits before lookup)
        "emergency exit north" to "Emergency Exit North", // E1
        "exit north" to "Emergency Exit North",
        "north exit" to "Emergency Exit North",
        "exit 1" to "Emergency Exit North", // Matches pre-processed "1 exit" or "exit 1"
        "first exit" to "Emergency Exit North",           // Matches pre-processed "1 exit"
        "1st exit" to "Emergency Exit North",

        "emergency exit floor 2" to "Emergency Exit Floor 2", // E2
        "exit floor 2" to "Emergency Exit Floor 2",
        "floor 2 exit" to "Emergency Exit Floor 2",
        "exit on floor 2" to "Emergency Exit Floor 2",
        "exit on the second floor" to "Emergency Exit Floor 2", // Matches pre-processed "exit on the 2 floor"
        "second floor exit" to "Emergency Exit Floor 2",       // Matches pre-processed "2 floor exit"
        "exit 2" to "Emergency Exit Floor 2", // Matches pre-processed "2 exit" or "exit 2"
        "second exit" to "Emergency Exit Floor 2",         // Matches pre-processed "2 exit"
        "2nd exit" to "Emergency Exit Floor 2"
        // REMOVED: Generic concepts like Restaurant, Cafe, Baggage Claim, Info Desk, etc.
    )

    // *** MODIFIED locationsExpectingNumbers: Only includes base types leading to numbered locations in JSON ***
    private val locationsExpectingNumbers: Set<String> = setOf(
        "Security Checkpoint" // Only this base type has a numbered instance (Security Checkpoint 1) in the JSON.
    )

    // Requires "gate" explicitly
    private val gateRegex = """gate\s+([a-zA-Z])\s?(\d{1,3})""".toRegex(RegexOption.IGNORE_CASE)

    // Ordinal words mapping used for pre-processing input
    private val ordinalWords = mapOf(
        "first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5",
        "1st" to "1", "2nd" to "2", "3rd" to "3", "4th" to "4", "5th" to "5"
    ). S("1", "2", "3", "4", "5") // Keep simple digits for consistency
    private fun <T> Map<String, String>.S(vararg elements: T) = this
    // Number words for base number extraction (e.g., Security Checkpoint one -> Security Checkpoint 1)
    private val numberWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10"
        // Limited to 10 for brevity, expand if needed for Check-in Desk X, etc. if they exist
    )
    // Pattern to find digits or number words for base number extraction
    private val numberPattern = Regex("""(?<=\s|^)(\d+|${numberWords.keys.joinToString("|")})(?=\s|$)""")

    // *** REDUCED fuzzyMatchMaxDistance for stricter matching ***
    private val fuzzyMatchMaxDistance = 1
    // --- End of Keywords, Maps, Regex ---

    // Helper function to pre-process text segments (lowercase + ordinals to digits)
    private fun preprocessText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var processed = text.lowercase(Locale.ROOT)
        ordinalWords.forEach { (word, digit) ->
            // Use regex for whole word replacement to avoid partial matches (e.g., "first" in "thirsty")
            processed = processed.replace("\\b${Regex.escape(word)}\\b".toRegex(), digit)
        }
        return processed
    }


    suspend fun processUserInput(userInput: String, currentState: MapStateInfo): LLMResponse {
        delay(400) // Simulate processing time
        val originalInputLower = userInput.lowercase(Locale.ROOT) // For broad trigger matching

        // --- Intent Prioritization ---
        // Priority: Lost > Distance > Navigation > Location Update > Confused > Fallback

        // 1. Check for Lost trigger
        if (lostTriggers.any { originalInputLower.contains(it) }) {
            return LLMResponse.FunctionCall("localizeUser", emptyMap())
        }

        // 2. Check for Distance Request trigger
        if (distanceTriggers.any { originalInputLower.contains(it) }) {
            return if (!currentState.isPathActive) {
                LLMResponse.GeneralResponse("You don't seem to have an active route planned. To check distance, please start navigation first. Where would you like to go?")
            } else {
                LLMResponse.FunctionCall("getDistance", emptyMap())
            }
        }

        // 3. Check for Navigation trigger
        val navigationTrigger = findTrigger(originalInputLower, navigationTriggers)
        if (navigationTrigger != null) {
            // Extract the raw destination string from the ORIGINAL input
            val triggerStartIndex = originalInputLower.lastIndexOf(navigationTrigger) // Use original case for index
            val textAfterNavTrigger = userInput.substring(triggerStartIndex + navigationTrigger.length).trimStart()
            var potentialDestinationStringRaw = textAfterNavTrigger.removePrefix("to ").removePrefix("the ").trim()
            potentialDestinationStringRaw = potentialDestinationStringRaw.replace(Regex("[.,!?]$"), "")

            // Pre-process the extracted destination string for parsing
            val potentialDestinationStringProcessed = preprocessText(potentialDestinationStringRaw)

            var detectedNavigationTarget: String? = null
            var needsNearestBathroom = false
            var needsNearestExit = false

            if (potentialDestinationStringProcessed.isNotBlank()) {
                // Try parsing specific location FIRST using the PROCESSED string
                detectedNavigationTarget = parseLocationName(potentialDestinationStringProcessed)

                // If specific parse failed, check RAW string for general keywords as fallback
                if (detectedNavigationTarget == null) {
                    // Use the raw extracted string (lowercase) for keyword check
                    val destLowerRaw = potentialDestinationStringRaw.lowercase(Locale.ROOT)
                    if (bathroomKeywords.any { destLowerRaw.contains(it) || isFuzzyMatchAny(potentialDestinationStringRaw, bathroomKeywords) }) {
                        needsNearestBathroom = true
                    } else if (exitKeywords.any { destLowerRaw.contains(it) || isFuzzyMatchAny(potentialDestinationStringRaw, exitKeywords) }) {
                        needsNearestExit = true
                    }
                }

                // Handle "my gate" separately (check the processed string for consistency)
                if (potentialDestinationStringProcessed.contains("my gate")) {
                    if (currentState.userFlightGate != null) {
                        detectedNavigationTarget = currentState.userFlightGate
                        needsNearestBathroom = false
                        needsNearestExit = false
                    } else {
                        detectedNavigationTarget = null
                        needsNearestBathroom = false
                        needsNearestExit = false
                    }
                }
            }

            // Determine the final target
            val effectiveNavigationTarget = when {
                detectedNavigationTarget != null -> detectedNavigationTarget
                needsNearestBathroom -> "BATHROOM"
                needsNearestExit -> "EMERGENCY_EXIT"
                else -> null
            }

            // Handle Navigation Action
            if (effectiveNavigationTarget != null) {
                if (currentState.currentKnownLocationId != null) {
                    return LLMResponse.FunctionCall("findPath", mapOf("destinationName" to effectiveNavigationTarget))
                } else {
                    val targetNameFeedback = when(effectiveNavigationTarget) {
                        "BATHROOM" -> "the nearest bathroom"
                        "EMERGENCY_EXIT" -> "the nearest emergency exit"
                        currentState.userFlightGate -> "your gate ($effectiveNavigationTarget)"
                        else -> "'${effectiveNavigationTarget}'" // Show canonical name
                    }
                    return LLMResponse.ClarificationNeeded("Okay, I can help you get to ${targetNameFeedback}. But first, where are you right now? Please tell me your location using 'I am at...'")
                }
            } else {
                // Target not identified, provide clarification using the ORIGINAL raw string
                if (potentialDestinationStringRaw.lowercase(Locale.ROOT).contains("my gate") && currentState.userFlightGate == null) {
                    return LLMResponse.ClarificationNeeded("I need your gate number to give directions. Which gate are you looking for?")
                } else if (potentialDestinationStringRaw.isNotBlank()) {
                    return LLMResponse.ClarificationNeeded("Sorry, I couldn't clearly identify '${potentialDestinationStringRaw}' as a known location like 'Gate A1', 'Bathroom 2', 'Second floor exit', 'Duty Free Shop', or 'VIP Lounge'. Could you try rephrasing?")
                } else {
                    return LLMResponse.ClarificationNeeded("Sorry, where exactly do you want to go? Please mention a gate, shop, or known area after '$navigationTrigger'.")
                }
            }
        } // End Navigation Trigger Handling

        // 4. Check for Location Update trigger (only if no navigation trigger was detected)
        val locationTrigger = findTrigger(originalInputLower, locationUpdateTriggers)
        if (locationTrigger != null) {
            // Extract raw location text from ORIGINAL input
            val triggerStartIndex = originalInputLower.indexOf(locationTrigger) // Use original case index
            val locationTriggerEndIndex = triggerStartIndex + locationTrigger.length
            var rawLocationText = userInput.substring(locationTriggerEndIndex).trimStart()
            rawLocationText = rawLocationText.removePrefix(":").trimStart()
            val potentialLocationStringRaw = rawLocationText.replace(Regex("[.,!?]$"), "").trim()

            // Pre-process for parsing
            val potentialLocationStringProcessed = preprocessText(potentialLocationStringRaw)

            // Try parsing location using PROCESSED string
            val detectedLocationUpdate = parseLocationName(potentialLocationStringProcessed)

            if (detectedLocationUpdate != null) {
                val currentDestination = currentState.currentDestinationId
                if (currentDestination != null && detectedLocationUpdate == currentDestination) {
                    return LLMResponse.GeneralResponse("Looks like you've arrived at your destination: ${detectedLocationUpdate}!")
                } else {
                    return LLMResponse.FunctionCall("updateLocation", mapOf("locationName" to detectedLocationUpdate))
                }
            } else {
                // Clarification uses the ORIGINAL raw string
                if (potentialLocationStringRaw.isNotBlank()){
                    return LLMResponse.ClarificationNeeded("Sorry, I heard you mention your location after '$locationTrigger', but couldn't identify '${potentialLocationStringRaw}' as a specific place like 'Main Entrance', 'Security Checkpoint 1', 'Duty Free Shop', 'VIP Lounge', 'Bathroom 1', or 'Exit North'. Where exactly are you?")
                } else {
                    return LLMResponse.ClarificationNeeded("Sorry, I heard you mention your location after '$locationTrigger', but couldn't identify the specific place. Where exactly are you?")
                }
            }
        } // End Location Update Trigger Handling

        // 5. Check for Confused Navigation trigger
        if (confusedNavigationTriggers.any { originalInputLower.contains(it) }) {
            if (currentState.currentKnownLocationId == null) {
                return LLMResponse.ClarificationNeeded("I can try to help, but I need to know where you are first. Can you tell me your current location or landmark?")
            }
            if (currentState.userFlightGate != null) {
                return LLMResponse.GeneralResponse(
                    "I see your flight is scheduled for Gate ${currentState.userFlightGate}. Would you like directions there?"
                            + " You can ask me 'take me to my gate'."
                )
            } else {
                return LLMResponse.GeneralResponse(
                    "Okay, I can try to help. Have you already checked in and passed security? " +
                            "Knowing your flight number or destination airline could help me find your gate. " +
                            "Otherwise, common next steps are check-in desks or the security checkpoint."
                )
            }
        } // End Confused Navigation Handling

        // 6. Fallback
        return LLMResponse.GeneralResponse(
            "Sorry, I didn't quite understand that. You can ask me things like:\n" +
                    "  - 'Where is Gate A1?'\n" +
                    "  - 'Take me to Bathroom 2.'\n" +
                    "  - 'Directions to the second floor exit.'\n" +
                    "  - 'I am at the VIP Lounge.'\n" +
                    "  - 'How far is my gate?'\n" +
                    "  - 'I'm lost.'\n" +
                    "  - 'I don't know where to go.'" // Removed examples with concepts not in the map
        )
    } // End processUserInput


    // --- Helper Functions ---

    private fun findTrigger(input: String, triggers: Set<String>): String? {
        // Find the longest trigger that is present (case-insensitive via inputLower)
        return triggers.filter { input.contains(it) }
            .maxByOrNull { it.length }
    }

    // *** MODIFIED: Assumes input 'processedLocationText' is already pre-processed (lowercase, ordinals->digits) ***
    private fun parseLocationName(processedLocationText: String): String? {
        if (processedLocationText.isBlank()) return null
        // Input is already processed (lowercase, ordinals replaced by digits)

        // 1. Check for Gate pattern first (requires "gate")
        gateRegex.find(processedLocationText)?.let { match ->
            val letter = match.groupValues[1].uppercase()
            val number = match.groupValues[2]
            return "Gate $letter$number" // Canonical format
        }

        // 2. Check for Exact Matches in Known Locations (longest match first)
        // Match the pre-processed input against the knownLocations keys
        val exactMatchKeyword = knownLocations.keys
            .filter { processedLocationText.contains(it) } // Direct contains check on processed text
            .maxByOrNull { it.length }

        if (exactMatchKeyword != null) {
            val canonicalName = knownLocations[exactMatchKeyword]!!
            var baseNameForNumberCheck: String? = null
            var requiresNumber = false
            // Check if the BASE TYPE expects a number (e.g. "Security Checkpoint")
            for (base in locationsExpectingNumbers) { // locationsExpectingNumbers is now just {"Security Checkpoint"}
                if (canonicalName.startsWith(base, ignoreCase = true)) {
                    baseNameForNumberCheck = base
                    requiresNumber = true
                    break
                }
                if (knownLocations[exactMatchKeyword]?.startsWith(base, ignoreCase = true) == true) {
                    baseNameForNumberCheck = base
                    requiresNumber = true
                }
            }

            if (requiresNumber && baseNameForNumberCheck != null) {
                // Look for number AFTER the keyword match IN THE PROCESSED INPUT
                val keywordEndIndex = processedLocationText.indexOf(exactMatchKeyword) + exactMatchKeyword.length
                val textAfterKeyword = processedLocationText.substring(keywordEndIndex).trimStart()

                // Use the simple numberPattern (digits/number words)
                numberPattern.find(textAfterKeyword)?.let { numMatch ->
                    val numVal = numMatch.value
                    val numDigit = numberWords[numVal] ?: numVal // Convert "one"->"1" etc.
                    val patternStartPosition = numMatch.range.first
                    if (patternStartPosition < 3) { // Number close to keyword
                        // Return the BASE name + space + number
                        return "$baseNameForNumberCheck $numDigit"
                    }
                }
                // If number required but not extracted, check if the canonical name itself IS the numbered one
                if (canonicalName.startsWith(baseNameForNumberCheck) && canonicalName.contains(Regex("""\s\d+$"""))) {
                    return canonicalName // Matched e.g. "security checkpoint 1" directly
                } else {
                    return null // Missing number for base type
                }
            } else {
                // No number required for this type OR it's a specific mapped location
                // (like "bathroom 2" -> "Restrooms near Gates B")
                return canonicalName // Return the matched canonical name
            }
        }

        // 3. Check for Fuzzy Matches (use processed input, stricter distance)
        var bestFuzzyMatchKeyword: String? = null
        var minFuzzyDistance = Int.MAX_VALUE
        for (keyword in knownLocations.keys.sortedByDescending { it.length }) {
            // Use the stricter distance
            val threshold = fuzzyMatchMaxDistance // Now 1
            // Fuzzy match against the processed input
            val distance = calculateFuzzyDistanceInSubstring(processedLocationText, keyword, threshold)

            if (distance != -1 && distance <= threshold) { // Check distance is within STRICT threshold
                if (bestFuzzyMatchKeyword == null || distance < minFuzzyDistance) {
                    // Check if this fuzzy match is for a base type expecting a number
                    val canonicalFuzzy = knownLocations[keyword]!!
                    val requiresNumberFuzzy = locationsExpectingNumbers.any { base -> canonicalFuzzy.startsWith(base, ignoreCase = true) }
                    val hasNumberSuffix = canonicalFuzzy.contains(Regex("""\s\d+$"""))

                    // Only accept fuzzy match if it's NOT for a base type missing a number
                    if (!requiresNumberFuzzy || hasNumberSuffix) {
                        minFuzzyDistance = distance
                        bestFuzzyMatchKeyword = keyword
                    }
                }
            }
        }
        if (bestFuzzyMatchKeyword != null) {
            return knownLocations[bestFuzzyMatchKeyword]!!
        }

        // 4. Fallback: No match
        return null
    }


    // Levenshtein Distance calculation (case-insensitive)
    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        // (Implementation unchanged)
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        val cost = if (s1[i - 1].equals(s2[j - 1], ignoreCase = true)) 0 else 1
                        dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                    }
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    // Fuzzy substring check, returns distance or -1
    private fun calculateFuzzyDistanceInSubstring(input: String, keyword: String, maxDistance: Int): Int {
        // (Implementation unchanged)
        if (keyword.length <= 2) return if (input.contains(keyword, ignoreCase = true)) 0 else -1
        // Optimization: If keyword itself is much shorter than maxDistance allows, simple contains might be enough? No, stick to Levenshtein.
        if (input.length < keyword.length - maxDistance) return -1

        var bestDist = -1
        val minSubLen = (keyword.length - maxDistance).coerceAtLeast(1)
        // Allow substring length to vary slightly around keyword length
        val maxSubLen = (keyword.length + maxDistance).coerceAtMost(input.length)

        for (i in 0..input.length - minSubLen) {
            val currentMaxLen = (maxSubLen).coerceAtMost(input.length - i)
            if (minSubLen > currentMaxLen) continue

            for (len in minSubLen..currentMaxLen) {
                val sub = input.substring(i, i + len)
                // Allow less deviation for length differences with stricter matching
                // Let's just use the raw maxDistance threshold here for simplicity with dist=1
                // val effectiveMaxDist = maxDistance // Simpler threshold = 1

                val dist = calculateLevenshteinDistance(sub, keyword)
                if (dist <= maxDistance) { // Use the strict maxDistance (1)
                    val startOk = (i == 0) || !input[i - 1].isLetterOrDigit()
                    val endOk = (i + len == input.length) || !input[i + len].isLetterOrDigit()
                    if (startOk && endOk) { // Only accept if whole word matches (boundaries ok)
                        if (bestDist == -1 || dist < bestDist) {
                            bestDist = dist
                        }
                    }
                }
            }
        }
        // Return distance only if found AND within the strict threshold
        return if (bestDist != -1 && bestDist <= maxDistance) bestDist else -1
    }

    // Checks if RAW input fuzzy matches any general keyword in the set (for nearest fallback)
    private fun isFuzzyMatchAny(rawInput: String, keywords: Set<String>): Boolean {
        if (rawInput.isBlank()) return false
        val inputLower = rawInput.lowercase(Locale.ROOT)
        return keywords.any { keyword ->
            // Use the strict fuzzy distance (1) here too for consistency? Yes.
            val threshold = fuzzyMatchMaxDistance
            calculateFuzzyDistanceInSubstring(inputLower, keyword, threshold) != -1
        }
    }
    // --- End of Helper Functions ---

} // End Class