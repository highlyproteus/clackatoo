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

#include "suggest/policyimpl/gesture/gesture_params.h"

#include <cstring>

namespace latinime {

int GestureParams::FIRST_LETTER_MAX_INDEX = 2;
int GestureParams::MAX_LOOKAHEAD_POINTS = 24;
float GestureParams::DOUBLE_LETTER_COST_STRONG = 0.3f;
float GestureParams::DOUBLE_LETTER_COST_WEAK = 0.8f;
float GestureParams::DOUBLE_LETTER_COST_NONE = 1.6f;
float GestureParams::FREE_PUNCTUATION_COST = 0.2f;
float GestureParams::LANGUAGE_WEIGHT = 8.0f;
int GestureParams::MAX_CACHE_DIC_NODE_SIZE = 500;
float GestureParams::SHAPE_WEIGHT = 0.3f;
float GestureParams::LETTER_COST = -2.0f;
float GestureParams::BASE_OUTPUT_SCORE = 1.0f;
float GestureParams::MAX_OUTPUT_SCORE_PER_INPUT = 0.4f;

bool GestureParams::set(const char *const name, const float value) {
#define SET_FLOAT(NAME) if (strcmp(name, #NAME) == 0) { NAME = value; return true; }
#define SET_INT(NAME) if (strcmp(name, #NAME) == 0) { NAME = static_cast<int>(value); return true; }
    SET_INT(FIRST_LETTER_MAX_INDEX)
    SET_INT(MAX_LOOKAHEAD_POINTS)
    SET_FLOAT(DOUBLE_LETTER_COST_STRONG)
    SET_FLOAT(DOUBLE_LETTER_COST_WEAK)
    SET_FLOAT(DOUBLE_LETTER_COST_NONE)
    SET_FLOAT(FREE_PUNCTUATION_COST)
    SET_FLOAT(LANGUAGE_WEIGHT)
    SET_INT(MAX_CACHE_DIC_NODE_SIZE)
    SET_FLOAT(SHAPE_WEIGHT)
    SET_FLOAT(LETTER_COST)
    SET_FLOAT(BASE_OUTPUT_SCORE)
    SET_FLOAT(MAX_OUTPUT_SCORE_PER_INPUT)
#undef SET_FLOAT
#undef SET_INT
    return false;
}
} // namespace latinime
