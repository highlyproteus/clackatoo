/*
 * Copyright (C) 2026 The Clackatoo Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "suggest/policyimpl/gesture/gesture_weighting.h"

#include <algorithm>
#include <cmath>

#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/layout/proximity_info_state.h"
#include "suggest/policyimpl/gesture/gesture_params.h"
#include "suggest/policyimpl/typing/scoring_params.h"
#include "utils/char_utils.h"

namespace latinime {

const GestureWeighting GestureWeighting::sInstance;

namespace {

float skipCost(const ProximityInfoState *const pis, const int index) {
    return pis->getProbability(index, NOT_AN_INDEX);
}

float doubleLetterCost(const DoubleLetterLevel level) {
    switch (level) {
        case A_STRONG_DOUBLE_LETTER:
            return GestureParams::DOUBLE_LETTER_COST_STRONG;
        case A_DOUBLE_LETTER:
            return GestureParams::DOUBLE_LETTER_COST_WEAK;
        default:
            return GestureParams::DOUBLE_LETTER_COST_NONE;
    }
}
} // namespace

float GestureWeighting::getMatchedCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode, DicNode_InputStateG *const inputStateG) const {
    const float maxCost = static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    const int codePoint = dicNode->getNodeCodePoint();
    const ProximityInfo *const proximityInfo = traverseSession->getProximityInfo();
    const int keyId = proximityInfo->getKeyIndexOf(CharUtils::toBaseLowerCase(codePoint));
    const int inputIndex = dicNode->getInputIndex(0);
    if (keyId == NOT_AN_INDEX) {
        if (CharUtils::isIntentionalOmissionCodePoint(codePoint)) {
            // Apostrophes and hyphens are not on the layout: the letter costs a little and uses
            // no stroke point.
            inputStateG->mNeedsToUpdateInputStateG = true;
            inputStateG->mPointerId = 0;
            inputStateG->mInputIndex = inputIndex;
            inputStateG->mPrevCodePoint = dicNode->getPrevCodePointG(0);
            return GestureParams::FREE_PUNCTUATION_COST;
        }
        return maxCost;
    }
    const ProximityInfoState *const pis = traverseSession->getProximityInfoState(0);
    const int size = pis->size();
    const int prevKeyId = proximityInfo->getKeyIndexOf(
            CharUtils::toBaseLowerCase(dicNode->getPrevCodePointG(0)));
    // Stroke length the previous letter's key is expected to be away from this letter's key.
    const bool useShape = GestureParams::SHAPE_WEIGHT > 0.0f && inputIndex > 0
            && prevKeyId != NOT_AN_INDEX;
    const float keyDistance = useShape ? static_cast<float>(
            proximityInfo->getKeyKeyDistanceG(prevKeyId, keyId)) : 0.0f;
    const float keyWidth = static_cast<float>(proximityInfo->getMostCommonKeyWidth());

    float bestCost = maxCost;
    int bestPoint = NOT_AN_INDEX;
    if (inputIndex < size) {
        int lastPoint = std::min(size - 1, inputIndex + GestureParams::MAX_LOOKAHEAD_POINTS);
        if (inputIndex == 0) {
            lastPoint = std::min(lastPoint, GestureParams::FIRST_LETTER_MAX_INDEX);
        }
        float skipped = 0.0f;
        for (int point = inputIndex; point <= lastPoint; ++point) {
            if (skipped >= bestCost) {
                break;
            }
            float cost = skipped + pis->getProbability(point, keyId);
            if (useShape && cost < maxCost) {
                const float strokeLength = static_cast<float>(
                        pis->getLengthCache(point) - pis->getLengthCache(inputIndex - 1));
                cost += GestureParams::SHAPE_WEIGHT * std::fabs(strokeLength - keyDistance)
                        / keyWidth;
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestPoint = point;
            }
            skipped += skipCost(pis, point);
        }
    }

    // The same key again: the stroke lingers on the previous point instead of moving on.
    bool staysOnPoint = false;
    if (inputIndex > 0 && inputIndex <= size && prevKeyId == keyId) {
        const float cost = doubleLetterCost(pis->getDoubleLetterLevel(inputIndex - 1));
        if (cost < bestCost) {
            bestCost = cost;
            staysOnPoint = true;
        }
    }

    if (bestCost >= maxCost) {
        return bestCost;
    }
    // Skipping a letter is cheaper than matching it (a point matched costs about as much as a
    // point skipped), so without a bonus per letter short words beat the word that was meant.
    bestCost += GestureParams::LETTER_COST;
    inputStateG->mNeedsToUpdateInputStateG = true;
    inputStateG->mPointerId = 0;
    inputStateG->mPrevCodePoint = codePoint;
    if (staysOnPoint) {
        inputStateG->mInputIndex = inputIndex;
        inputStateG->mDoubleLetterLevel = pis->getDoubleLetterLevel(inputIndex - 1);
    } else {
        inputStateG->mInputIndex = bestPoint + 1;
        inputStateG->mDoubleLetterLevel = NOT_A_DOUBLE_LETTER;
    }
    return bestCost;
}

// All stroke points are used up. The only letter that still fits is the previous letter's key again:
// the stroke ended on a double letter ("all", "free"), which costs like any other repeated key.
float GestureWeighting::getCompletionCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode) const {
    const ProximityInfo *const proximityInfo = traverseSession->getProximityInfo();
    const int keyId = proximityInfo->getKeyIndexOf(
            CharUtils::toBaseLowerCase(dicNode->getNodeCodePoint()));
    const int prevKeyId = proximityInfo->getKeyIndexOf(
            CharUtils::toBaseLowerCase(dicNode->getPrevCodePointG(0)));
    if (keyId == NOT_AN_INDEX || keyId != prevKeyId) {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }
    const ProximityInfoState *const pis = traverseSession->getProximityInfoState(0);
    return doubleLetterCost(pis->getDoubleLetterLevel(pis->size() - 1));
}

// Points the word did not reach are skipped: the endpoints of a stroke have high skip costs, so a
// word has to extend to the end of the stroke.
float GestureWeighting::getTerminalInsertionCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode) const {
    const ProximityInfoState *const pis = traverseSession->getProximityInfoState(0);
    const int size = pis->size();
    float cost = 0.0f;
    for (int point = dicNode->getInputIndex(0); point < size; ++point) {
        cost += skipCost(pis, point);
    }
    return cost;
}

float GestureWeighting::getTerminalLanguageCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode, const float dicNodeLanguageImprobability) const {
    return dicNodeLanguageImprobability * ScoringParams::DISTANCE_WEIGHT_LANGUAGE
            * GestureParams::LANGUAGE_WEIGHT;
}

float GestureWeighting::getTerminalSpatialCost(const DicTraverseSession *const traverseSession,
        const DicNode *const dicNode) const {
    return 0.0f;
}
} // namespace latinime
