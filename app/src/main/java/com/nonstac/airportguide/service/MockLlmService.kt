package com.nonstac.airportguide.service

import com.nonstac.airportguide.data.model.LLMResponse
import com.nonstac.airportguide.data.model.MapStateInfo // Assuming MapStateInfo has destination info
// If MapStateInfo doesn't have destinationInfo, remove the import and the arrival check logic.
// You might need to define destinationInfo within MapStateInfo as suggested in previous comments.
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.abs

// Service to process user input, with fuzzy matching, compound queries (revised), and arrival detection.
class MockLlmService {

    // --- Keyword Sets, Maps, Regex (largely unchanged) ---
    private val locationUpdateTriggers = setOf(
        "i am at", "i'm at", "my location is", "currently at",
        "located at", "presently at", "find me at", "here at",
        "i am near", "i'm near", "i am next to", "i'm next to"
    )
    // Added more conjunctions to potentially split compound queries during extraction
    private val navigationTriggers = setOf(
        "how do i get to", "how do you get to",
        "take me to", "go to", "where is", "directions to", "path to",
        "which way to", "navigate to", "show me", "guide me to", "lead me to",
        // Conjunctions signalling navigation after something else
        "and i want to go to", "and i want directions to", "and i need to get to",
        "and take me to", "and where is", "and how do i get to",
        ", take me to", ", go to", ", directions to" // Comma variations
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
    private val bathroomKeywords = setOf("bathroom", "restroom", "toilet", "wc", "washroom", "lavatory", "loo")
    private val exitKeywords = setOf("exit", "emergency exit", "way out", "emergency door")

    private val knownLocations: Map<String, String> = mapOf(
        "security checkpoint" to "Security Checkpoint", "security check point" to "Security Checkpoint", "security" to "Security Checkpoint",
        "check in desk" to "Check-in Desk", "check in" to "Check-in Desk", "checkin desk" to "Check-in Desk", "checkin" to "Check-in Desk",
        "desk" to "Desk", "counter" to "Counter", "terminal" to "Terminal", "zone" to "Zone", "area" to "Area", "level" to "Level", "floor" to "Floor",
        "entrance" to "Entrance", "exit" to "Exit",
        "duty free" to "Duty Free", "dutyfree" to "Duty Free", "duty free shop" to "Duty Free", "dutyfree shop" to "Duty Free",
        "lounge" to "Lounge", "information" to "Information Desk", "information desk" to "Information Desk",
        "baggage claim" to "Baggage Claim", "food court" to "Food Court", "cafe" to "Cafe", "restaurant" to "Restaurant"
    )

    private val locationsExpectingNumbers: Set<String> = setOf(
        "Security Checkpoint", "Check-in Desk", "Desk", "Counter", "Terminal", "Zone", "Area", "Level", "Floor"
    )

    private val gateRegex = """(?:gate\s?)?([a-zA-Z])(\d{1,3})""".toRegex(RegexOption.IGNORE_CASE)
    private val numberWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19", "twenty" to "20"
    )
    private val numberPattern = Regex("""(\d+|${numberWords.keys.joinToString("|")})""")
    private val fuzzyMatchMaxDistance = 2
    // --- End of Keywords, Maps, Regex ---

    suspend fun processUserInput(userInput: String, currentState: MapStateInfo): LLMResponse {
        delay(400) // Simulate processing time
        val inputLower = userInput.lowercase(Locale.ROOT)

        // Variables to store detected entities/intents
        var detectedLocationUpdate: String? = null
        var detectedNavigationTarget: String? = null
        var needsNearestBathroom = false
        var needsNearestExit = false
        var detectedDistanceRequest = false
        var detectedLostLocalization = false
        var detectedConfusedNavigation = false

        // --- Stage 1: Entity Extraction and Intent Detection (REVISED for Compound Queries) ---

        var potentialLocationString: String? = null
        var potentialDestinationString: String? = null

        // A. Try to find Location Update components
        // Find the best (longest) location trigger first
        val locationTrigger = findTrigger(inputLower, locationUpdateTriggers)
        var locationTriggerEndIndex = -1
        if (locationTrigger != null) {
            val triggerStartIndex = inputLower.indexOf(locationTrigger)
            locationTriggerEndIndex = triggerStartIndex + locationTrigger.length
            // Extract text immediately following the trigger
            var rawLocationText = userInput.substring(locationTriggerEndIndex).trimStart()
            rawLocationText = rawLocationText.removePrefix(":").trimStart() // Handle "at: location"

            // Try to delimit the location phrase (e.g., stop at conjunctions or nav triggers)
            val stopPhrases = listOf(" and ", " then ", ", ", " how do i", " take me", " where is") // Simplified delimiters
            var stopIndex = -1
            for (phrase in stopPhrases) {
                val index = rawLocationText.lowercase(Locale.ROOT).indexOf(phrase)
                if (index != -1 && (stopIndex == -1 || index < stopIndex)) {
                    stopIndex = index
                }
            }
            if (stopIndex != -1) {
                potentialLocationString = rawLocationText.substring(0, stopIndex).trim()
            } else {
                potentialLocationString = rawLocationText.trim()
            }
            potentialLocationString = potentialLocationString?.replace(Regex("[.,!?]$"), "") // Clean end punc
        }

        // B. Try to find Navigation components (Search independently)
        // Find the best (longest) navigation trigger
        val navigationTrigger = findTrigger(inputLower, navigationTriggers)
        if (navigationTrigger != null) {
            val triggerStartIndex = inputLower.lastIndexOf(navigationTrigger) // Use lastIndexOf in case trigger appears early non-navigationally?
            val textAfterNavTrigger = userInput.substring(triggerStartIndex + navigationTrigger.length).trimStart()

            // Extract the destination phrase after the trigger
            potentialDestinationString = textAfterNavTrigger.removePrefix("to ").removePrefix("the ").trim()
            potentialDestinationString = potentialDestinationString.replace(Regex("[.,!?]$"), "") // Clean end punc

            // Avoid grabbing location info if nav trigger appeared *before* location trigger
            if (locationTriggerEndIndex != -1 && triggerStartIndex < locationTriggerEndIndex) {
                // Navigation trigger appeared before the identified location trigger ended.
                // This might be complex phrasing, e.g. "How do I get to Gate A5, I am at Security 1"
                // In this case, potentialDestinationString might contain location info. Re-evaluate.
                // For simplicity now, we might misinterpret this. A full parser is needed for perfect handling.
                // Let's assume simpler "Loc -> Nav" or just "Nav" structure primarily.
            }
        }

        // C. Parse the potential strings
        if (!potentialLocationString.isNullOrBlank()) {
            detectedLocationUpdate = parseLocationName(potentialLocationString)
        }

        if (!potentialDestinationString.isNullOrBlank()) {
            val destLower = potentialDestinationString.lowercase(Locale.ROOT)
            if (destLower.contains("my gate")) { // "my gate" requires special handling
                if (currentState.userFlightGate != null) detectedNavigationTarget = currentState.userFlightGate
            } else if (bathroomKeywords.any { destLower.contains(it) || isFuzzyMatchAny(potentialDestinationString, bathroomKeywords) }) {
                needsNearestBathroom = true
            } else if (exitKeywords.any { destLower.contains(it) || isFuzzyMatchAny(potentialDestinationString, exitKeywords) }) {
                needsNearestExit = true
            } else {
                // Parse as a regular location/gate only if not a special keyword handled above
                detectedNavigationTarget = parseLocationName(potentialDestinationString)
            }
        }

        // D/E/F. Check other intents
        if (distanceTriggers.any { inputLower.contains(it) }) detectedDistanceRequest = true
        if (lostTriggers.any { inputLower.contains(it) }) detectedLostLocalization = true
        // Check confused nav only if primary intents (loc update, navigation) weren't strongly detected
        if (detectedLocationUpdate == null && detectedNavigationTarget == null && !needsNearestBathroom && !needsNearestExit && !detectedLostLocalization) {
            if (confusedNavigationTriggers.any { inputLower.contains(it) }) {
                detectedConfusedNavigation = true
            }
        }

        // --- Stage 2: Decision Logic based on detected intents ---

        // Priority Order: Lost > Arrival > Distance > Navigation > Location Update > Confused Nav > Fallback

        // 1. Handle "Lost"
        if (detectedLostLocalization) {
            return LLMResponse.FunctionCall("localizeUser", emptyMap())
        }

        // 2. Handle Arrival Check
        val currentDestination = currentState.currentDestinationId
        if (detectedLocationUpdate != null && currentDestination != null && detectedLocationUpdate == currentDestination) {
            return LLMResponse.GeneralResponse("Looks like you've arrived at your destination: ${detectedLocationUpdate}!")
        }

        // 3. Handle Distance Request
        if (detectedDistanceRequest) {
            return if (!currentState.isPathActive) {
                LLMResponse.GeneralResponse("You don't seem to have an active route planned. To check distance, please start navigation first. Where would you like to go?")
            } else {
                LLMResponse.FunctionCall("getDistance", emptyMap())
            }
        }

        // 4. Handle Navigation (now correctly uses variables populated by revised Stage 1)
        val effectiveNavigationTarget = when {
            needsNearestBathroom -> "BATHROOM"
            needsNearestExit -> "EMERGENCY_EXIT"
            else -> detectedNavigationTarget
        }

        if (effectiveNavigationTarget != null) {
            // Location is known if already in state OR provided in this compound query
            val locationKnown = currentState.currentKnownLocationId != null || detectedLocationUpdate != null
            if (locationKnown) {
                // Implicitly uses updated location if provided now. External system handles state.
                return LLMResponse.FunctionCall("findPath", mapOf("destinationName" to effectiveNavigationTarget))
            } else {
                // Location unknown, ask for it.
                val targetNameFeedback = when(effectiveNavigationTarget) {
                    "BATHROOM" -> "the nearest bathroom"
                    "EMERGENCY_EXIT" -> "the nearest emergency exit"
                    currentState.userFlightGate -> "your gate ($effectiveNavigationTarget)" // Requires userFlightGate != null here
                    else -> effectiveNavigationTarget
                }
                return LLMResponse.ClarificationNeeded("Okay, I can help you get to ${targetNameFeedback}. But first, where are you right now?")
            }
        }
        // Handle case where nav trigger found, target was "my gate", but gate unknown
        if (navigationTrigger != null && effectiveNavigationTarget == null && (potentialDestinationString?.lowercase(Locale.ROOT)?.contains("my gate") == true || inputLower.contains("my gate"))) {
            return LLMResponse.ClarificationNeeded("I need your gate number to give directions. Which gate are you looking for?")
        }

        // 5. Handle Location Update (Only if no higher priority intent was actionable AND not arrival)
        if (detectedLocationUpdate != null) {
            // Removed FunctionCallWithFollowUp, just call the function.
            return LLMResponse.FunctionCall("updateLocation", mapOf("locationName" to detectedLocationUpdate))
        }

        // 6. Handle Confused Navigation (if no other action taken)
        if (detectedConfusedNavigation) {
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
        }

        // 7. Fallback (Refined based on partial detections)
        if (navigationTrigger != null && effectiveNavigationTarget == null && !(potentialDestinationString?.lowercase(Locale.ROOT)?.contains("my gate") == true || inputLower.contains("my gate"))) {
            // Nav trigger found, but target not parsed/identified (and wasn't 'my gate')
            val potentialDest = potentialDestinationString ?: extractPotentialDestination(inputLower, navigationTriggers, navigationTrigger) // Fallback extraction
            if (!potentialDest.isNullOrBlank()) {
                return LLMResponse.ClarificationNeeded("Sorry, I couldn't clearly identify '$potentialDest' as a known destination or area. Could you try rephrasing or specifying a gate, shop, or area like 'Security Checkpoint 1' or 'Duty Free'?")
            } else {
                return LLMResponse.ClarificationNeeded("Sorry, where exactly do you want to go? Please mention a gate, shop, or known area.")
            }
        }
        if (locationTrigger != null && detectedLocationUpdate == null) {
            // Loc trigger found, but location not parsed
            return LLMResponse.ClarificationNeeded("Sorry, I heard you mention your location, but couldn't identify the specific place. Where exactly are you?")
        }

        // Generic fallback
        return LLMResponse.GeneralResponse(
            "Sorry, I didn't quite understand that. You can ask me things like:\n" +
                    "  - 'Where is Gate C5?'\n" +
                    "  - 'Take me to the nearest restroom.'\n" +
                    "  - 'I am near the duty free shop and want to go to Gate A1.'\n" +
                    "  - 'How far is my gate?'\n" +
                    "  - 'I'm lost.'\n" +
                    "  - 'I don't know where to go.'"
        )
    } // End processUserInput


    // --- Helper Functions ---

    private fun findTrigger(input: String, triggers: Set<String>): String? {
        // Find the longest trigger that is present
        return triggers.filter { input.contains(it) }
            .maxByOrNull { it.length }
    }

    // extractPotentialDestination - Revised to be simpler as main logic uses direct substring now
    // This might only be useful for fallback clarification now.
    private fun extractPotentialDestination(inputLower: String, triggers: Set<String>, usedTrigger: String): String? {
        val textAfterTrigger = inputLower.substringAfter(usedTrigger, "").trim()
        if (textAfterTrigger.isNotBlank()) {
            val potential = textAfterTrigger.replace(Regex("[.,!?]"), "").trim()
            if (potential.isNotBlank() && potential.length > 1) return potential.ifEmpty { null }
        }
        return null
    }

    private fun parseLocationName(rawLocation: String): String? {
        if (rawLocation.isBlank()) return null
        val inputClean = rawLocation.replace(Regex("[.,!?]$"), "").trim() // Clean input once
        val inputLower = inputClean.lowercase(Locale.ROOT)
        if (inputLower.isBlank()) return null


        // 1. Check for Gate pattern
        gateRegex.find(inputLower)?.let { match ->
            return "Gate ${match.groupValues[1].uppercase()}${match.groupValues[2]}"
        }

        // 2. Check for Exact Matches in Known Locations (prioritize longer matches)
        val exactMatchKeyword = knownLocations.keys
            .filter { inputLower.contains(it) }
            .maxByOrNull { it.length }

        if (exactMatchKeyword != null) {
            val canonical = knownLocations[exactMatchKeyword]!!
            val requiresNumber = locationsExpectingNumbers.contains(canonical)

            if (requiresNumber) {
                // More careful number extraction: look for number AFTER the keyword match IN inputLower
                val keywordEndIndex = inputLower.indexOf(exactMatchKeyword) + exactMatchKeyword.length
                val textAfterKeyword = inputLower.substring(keywordEndIndex).trimStart()

                numberPattern.find(textAfterKeyword)?.let { numMatch ->
                    val numVal = numMatch.groupValues[1]
                    val numDigit = numberWords[numVal] ?: numVal
                    // Check if number appears immediately after keyword (allow minor chars like space, dash)
                    if (textAfterKeyword.startsWith(numVal) || textAfterKeyword.startsWith(numDigit) || textAfterKeyword.startsWith("-$numVal") || textAfterKeyword.startsWith(" $numVal")) {
                        return "$canonical $numDigit"
                    }
                }
                return null // Incomplete numbered location
            } else {
                // Ensure the exact match isn't just part of a different numbered location base word
                val couldBeNumberedBase = locationsExpectingNumbers.any{ base -> canonical.startsWith(base, ignoreCase=true) && canonical != base }
                if(couldBeNumberedBase && numberPattern.containsMatchIn(inputLower.substringAfter(exactMatchKeyword))) {
                    return null // Ambiguous, looks like a numbered loc but number failed extraction
                }
                return canonical // Matched non-numbered exactly
            }
        }

        // 3. Check for Fuzzy Matches (using complex substring check)
        var bestFuzzyMatchKeyword: String? = null
        for (keyword in knownLocations.keys.sortedByDescending { it.length }) {
            val threshold = fuzzyMatchMaxDistance + (keyword.length / 6)
            if (isFuzzyMatch(inputLower, keyword, threshold)) { // Use the complex substring check
                bestFuzzyMatchKeyword = keyword
                break
            }
        }

        if (bestFuzzyMatchKeyword != null) {
            val canonical = knownLocations[bestFuzzyMatchKeyword]!!
            val requiresNumber = locationsExpectingNumbers.contains(canonical)
            if (requiresNumber) {
                return null // Number extraction too unreliable after fuzzy match
            } else {
                return canonical // Matched non-numbered fuzzily
            }
        }

        // 4. Fallback: No known pattern/location matched.
        return null
    }

    // --- Fuzzy Matching Helpers ---

    private fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        // Standard Levenshtein implementation
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        val cost = if (s1[i - 1].equals(s2[j - 1], ignoreCase = true)) 0 else 1 // Case-insensitive cost
                        dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                    }
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    /** Complex fuzzy match: Checks if the keyword fuzzy matches any relevant substring of the input */
    private fun isFuzzyMatch(input: String, keyword: String, maxDistance: Int): Boolean {
        if (keyword.length <= 2) return input.contains(keyword, ignoreCase = true) // Exact match for short keywords
        if (input.length < keyword.length - maxDistance) return false // Input significantly shorter

        val minSubLen = (keyword.length - maxDistance).coerceAtLeast(1)
        val maxSubLen = keyword.length + maxDistance

        for (i in 0 until input.length) {
            for (len in minSubLen..maxSubLen) {
                if (i + len > input.length) break

                val sub = input.substring(i, i + len)
                val effectiveMaxDist = maxDistance + abs(sub.length - keyword.length) / 2 // Allow less deviation based on length diff

                if (calculateLevenshteinDistance(sub, keyword) <= effectiveMaxDist) {
                    // Basic Boundary Check: Try to avoid matching mid-word
                    val startOk = (i == 0) || !input[i - 1].isLetterOrDigit()
                    val endOk = (i + len == input.length) || !input[i + len].isLetterOrDigit()
                    if (startOk && endOk) {
                        return true
                    }
                    // If no boundary match, still consider it a potential match for now (less strict)
                    // return true // Uncomment for less strict matching
                }
            }
        }
        return false
    }

    /** Checks if the input string fuzzy matches any of the keywords in the set */
    private fun isFuzzyMatchAny(input: String, keywords: Set<String>): Boolean {
        if (input.isBlank()) return false
        return keywords.any { keyword ->
            val threshold = fuzzyMatchMaxDistance + (keyword.length / 6)
            isFuzzyMatch(input, keyword, threshold) // Use complex substring check
        }
    }
    // --- End of Helper Functions ---

} // End Class