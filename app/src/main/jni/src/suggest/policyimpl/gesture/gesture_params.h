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

#ifndef LATINIME_GESTURE_PARAMS_H
#define LATINIME_GESTURE_PARAMS_H

#include "defines.h"

namespace latinime {

// Tunable constants of the gesture (swipe) decoder. Production code only reads them; the
// evaluation harness (tools/gesture-eval) overrides them through set() before any query. The
// values are written to a static initialiser and never change while a query is running.
struct GestureParams {
    // Highest sampled point the first letter may align to.
    static int FIRST_LETTER_MAX_INDEX;
    // Most sampled points one letter may skip ahead.
    static int MAX_LOOKAHEAD_POINTS;
    // Cost of a repeated key when the stroke lingers on it (strong / weak / no double letter).
    static float DOUBLE_LETTER_COST_STRONG;
    static float DOUBLE_LETTER_COST_WEAK;
    static float DOUBLE_LETTER_COST_NONE;
    // Cost of an apostrophe or hyphen that is not on the layout.
    static float FREE_PUNCTUATION_COST;
    // Multiplier on ScoringParams::DISTANCE_WEIGHT_LANGUAGE.
    static float LANGUAGE_WEIGHT;
    // Search beam: number of nodes kept per letter.
    static int MAX_CACHE_DIC_NODE_SIZE;
    // Added to the cost of every matched letter (negative: longer words are favoured).
    static float LETTER_COST;
    // Weight of the shape term: how far the stroke length between two letters' points is from
    // the distance between their keys, in key widths.
    static float SHAPE_WEIGHT;
    // Scoring constants (see GestureScoring::calculateFinalScore).
    static float BASE_OUTPUT_SCORE;
    static float MAX_OUTPUT_SCORE_PER_INPUT;

    // Sets the parameter with the given name. Returns false for an unknown name.
    static bool set(const char *name, float value);

 private:
    GestureParams() = delete;
};
} // namespace latinime
#endif // LATINIME_GESTURE_PARAMS_H
