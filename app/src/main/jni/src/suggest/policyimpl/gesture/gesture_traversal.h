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

#ifndef LATINIME_GESTURE_TRAVERSAL_H
#define LATINIME_GESTURE_TRAVERSAL_H

#include "defines.h"
#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/dicnode/dic_node_vector.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/layout/proximity_info_state.h"
#include "suggest/core/policy/traversal.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/policyimpl/gesture/gesture_params.h"
#include "suggest/policyimpl/typing/scoring_params.h"
#include "utils/char_utils.h"

namespace latinime {

// Which dictionary letters may follow a stroke position. A letter is searched when its key lies on
// the stroke at or after the node's input index; typing error corrections are all off.
class GestureTraversal : public Traversal {
 public:
    static const GestureTraversal *getInstance() { return &sInstance; }

    // MAX_POINTER_COUNT_G makes DicTraverseSession::setupForGetSuggestions treat input as geometric.
    AK_FORCE_INLINE int getMaxPointerCount() const {
        return MAX_POINTER_COUNT_G;
    }

    AK_FORCE_INLINE bool allowsErrorCorrections(const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool isOmission(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, const DicNode *const childDicNode,
            const bool allowsErrorCorrections) const {
        return false;
    }

    AK_FORCE_INLINE bool isSpaceSubstitutionTerminal(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool isSpaceOmissionTerminal(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool shouldDepthLevelCache(
            const DicTraverseSession *const traverseSession) const {
        return false;
    }

    AK_FORCE_INLINE bool shouldNodeLevelCache(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool canDoLookAheadCorrection(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE ProximityType getProximityType(
            const DicTraverseSession *const traverseSession, const DicNode *const dicNode,
            const DicNode *const childDicNode) const {
        const int codePoint = childDicNode->getNodeCodePoint();
        const ProximityInfo *const proximityInfo = traverseSession->getProximityInfo();
        const int keyId = proximityInfo->getKeyIndexOf(CharUtils::toBaseLowerCase(codePoint));
        if (keyId == NOT_AN_INDEX) {
            return CharUtils::isIntentionalOmissionCodePoint(codePoint) ? MATCH_CHAR
                    : UNRELATED_CHAR;
        }
        const ProximityInfoState *const pis = traverseSession->getProximityInfoState(0);
        const int inputIndex = dicNode->getInputIndex(0);
        if (inputIndex < pis->size() && pis->isKeyInSerchKeysAfterIndex(inputIndex, keyId)) {
            return MATCH_CHAR;
        }
        // The stroke lingers on the previous key (double letter).
        if (inputIndex > 0 && proximityInfo->getKeyIndexOf(
                CharUtils::toBaseLowerCase(dicNode->getPrevCodePointG(0))) == keyId) {
            return MATCH_CHAR;
        }
        return UNRELATED_CHAR;
    }

    AK_FORCE_INLINE bool needsToTraverseAllUserInput() const {
        return true;
    }

    AK_FORCE_INLINE float getMaxSpatialDistance() const {
        return ScoringParams::MAX_SPATIAL_DISTANCE;
    }

    AK_FORCE_INLINE int getDefaultExpandDicNodeSize() const {
        return DicNodeVector::DEFAULT_NODES_SIZE_FOR_OPTIMIZATION;
    }

    AK_FORCE_INLINE int getMaxCacheSize(const int inputSize, const float weightForLocale) const {
        return GestureParams::MAX_CACHE_DIC_NODE_SIZE;
    }

    AK_FORCE_INLINE int getTerminalCacheSize() const {
        return MAX_RESULTS;
    }

    AK_FORCE_INLINE bool isPossibleOmissionChildNode(
            const DicTraverseSession *const traverseSession, const DicNode *const parentDicNode,
            const DicNode *const dicNode) const {
        return false;
    }

    AK_FORCE_INLINE bool isGoodToTraverseNextWord(const DicNode *const dicNode,
            const int probability) const {
        return false;
    }

 private:
    DISALLOW_COPY_AND_ASSIGN(GestureTraversal);
    static const GestureTraversal sInstance;

    GestureTraversal() {}
    ~GestureTraversal() {}
};
} // namespace latinime
#endif // LATINIME_GESTURE_TRAVERSAL_H
