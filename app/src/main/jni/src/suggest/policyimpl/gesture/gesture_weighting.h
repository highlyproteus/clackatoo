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

#ifndef LATINIME_GESTURE_WEIGHTING_H
#define LATINIME_GESTURE_WEIGHTING_H

#include "defines.h"
#include "suggest/core/dictionary/error_type_utils.h"
#include "suggest/core/policy/weighting.h"
#include "suggest/core/session/dic_traverse_session.h"

namespace latinime {

class DicNode;
struct DicNode_InputStateG;
class MultiBigramMap;

// Costs for gesture input. A dictionary letter is aligned to one of the sampled stroke points at or
// after the node's input index; the points in between are skipped at their skip cost. Every error
// correction of typing input (omission, insertion, transposition, ...) is forbidden.
class GestureWeighting : public Weighting {
 public:
    static const GestureWeighting *getInstance() { return &sInstance; }

 protected:
    float getTerminalSpatialCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode) const;

    float getOmissionCost(const DicNode *const parentDicNode,
            const DicNode *const dicNode) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    float getMatchedCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, DicNode_InputStateG *inputStateG) const;

    bool isProximityDicNode(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode) const {
        return false;
    }

    float getTranspositionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const parentDicNode, const DicNode *const dicNode) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    float getInsertionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const parentDicNode, const DicNode *const dicNode) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    float getSpaceOmissionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, DicNode_InputStateG *inputStateG) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    float getNewWordBigramLanguageCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, MultiBigramMap *const multiBigramMap) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    // A word longer than the stroke is never completed, except for a double letter at its end.
    float getCompletionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode) const;

    float getTerminalInsertionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode) const;

    float getTerminalLanguageCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, const float dicNodeLanguageImprobability) const;

    bool needsToNormalizeCompoundDistance() const { return false; }

    float getAdditionalProximityCost() const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    float getSubstitutionCost() const { return static_cast<float>(MAX_VALUE_FOR_WEIGHTING); }

    float getSpaceSubstitutionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode) const {
        return static_cast<float>(MAX_VALUE_FOR_WEIGHTING);
    }

    ErrorTypeUtils::ErrorType getErrorType(const CorrectionType correctionType,
            const DicTraverseSession *const traverseSession,
            const DicNode *const parentDicNode, const DicNode *const dicNode) const {
        return ErrorTypeUtils::NOT_AN_ERROR;
    }

 private:
    DISALLOW_COPY_AND_ASSIGN(GestureWeighting);
    static const GestureWeighting sInstance;

    GestureWeighting() {}
    ~GestureWeighting() {}
};
} // namespace latinime
#endif // LATINIME_GESTURE_WEIGHTING_H
