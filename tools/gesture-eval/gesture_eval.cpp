// SPDX-License-Identifier: GPL-3.0-only
//
// Host harness for the gesture (swipe) decoder: loads a binary dictionary, builds a keyboard
// layout and runs gesture queries through the same native code path as the keyboard.
//
//   gesture_eval --dict <path.dict> [--selftest] [--param NAME=VALUE ...]
//
// Protocol on stdin/stdout (one record per line, decimal numbers, words are UTF-8 without spaces):
//   in:  G <id> <kbWidth> <kbHeight> <keyCount> <pointCount>
//        K <left> <top> <width> <height> <codePoint>        (keyCount times)
//        P <x> <y> <millis> <pointerId>                      (pointCount times)
//   out: R <id> <micros> <n> <word1> <score1> ... <wordN> <scoreN>      (best first)
//   in:  Q <id> <word>
//   out: IN <id> 1|0                                (the word is / is not in the dictionary)
//
// The keyboard geometry code is a C++ port of computeNearestNeighbors() and
// createNativeProximityInfo() in ProximityInfo.java; the query follows
// latinime_BinaryDictionary_getSuggestions().

#include <sys/stat.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "defines.h"
#include "dictionary/property/ngram_context.h"
#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"
#include "suggest/core/dictionary/dictionary.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/result/suggestion_results.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/core/suggest_options.h"
#include "suggest/policyimpl/gesture/gesture_params.h"
#include "utils/int_array_view.h"

using namespace latinime;

namespace {

constexpr int GRID_WIDTH = 32;   // config_keyboard_grid_width
constexpr int GRID_HEIGHT = 16;  // config_keyboard_grid_height
constexpr int MAX_PROXIMITY_CHARS = 16;  // ProximityInfo.MAX_PROXIMITY_CHARS_SIZE
constexpr float SEARCH_DISTANCE = 1.2f;  // ProximityInfo.SEARCH_DISTANCE
constexpr int NOT_A_CODE = -1;           // Constants.NOT_A_CODE

struct KeyRect {
    int left, top, width, height, code;

    int squaredDistanceToEdge(const int x, const int y) const {
        const int right = left + width;
        const int bottom = top + height;
        const int edgeX = x < left ? left : std::min(x, right);
        const int edgeY = y < top ? top : std::min(y, bottom);
        const int dx = x - edgeX;
        const int dy = y - edgeY;
        return dx * dx + dy * dy;
    }
};

struct Point {
    int x, y, millis, pointerId;
};

// Most frequent value of `values` (first one reached wins ties, like KeyboardParams' histogram).
int mostCommon(const std::vector<int> &values) {
    std::map<int, int> counts;
    int best = 0;
    int bestCount = 0;
    for (const int v : values) {
        const int c = ++counts[v];
        if (c > bestCount) {
            bestCount = c;
            best = v;
        }
    }
    return best;
}

// The keyboard's "most common key width/height" is the pitch: key size plus the gap to the
// neighbour. The data only has key rectangles, so the gap is estimated from the layout.
void computeMostCommonKeySize(const std::vector<KeyRect> &keys, int *outWidth, int *outHeight) {
    std::vector<int> widths, heights, hGaps, vGaps;
    for (const KeyRect &key : keys) {
        widths.push_back(key.width);
        heights.push_back(key.height);
    }
    for (size_t i = 0; i + 1 < keys.size(); ++i) {
        if (keys[i].top == keys[i + 1].top) {
            const int gap = keys[i + 1].left - (keys[i].left + keys[i].width);
            if (gap > 0) hGaps.push_back(gap);
        } else {
            // Next row: gap to the first key below that overlaps horizontally.
            for (size_t j = i + 1; j < keys.size(); ++j) {
                if (keys[j].top > keys[i].top && keys[j].left < keys[i].left + keys[i].width
                        && keys[j].left + keys[j].width > keys[i].left) {
                    const int gap = keys[j].top - (keys[i].top + keys[i].height);
                    if (gap > 0) vGaps.push_back(gap);
                    break;
                }
            }
        }
    }
    *outWidth = mostCommon(widths) + (hGaps.empty() ? 0 : mostCommon(hGaps));
    *outHeight = mostCommon(heights) + (vGaps.empty() ? 0 : mostCommon(vGaps));
}

// Everything that outlives a ProximityInfo: it copies its arrays, so locals are enough.
std::unique_ptr<ProximityInfo> buildProximityInfo(const int kbWidth, const int kbHeight,
        std::vector<KeyRect> keys) {
    // Keyboard.getSortedKeys(): top-left to bottom-right.
    std::stable_sort(keys.begin(), keys.end(), [](const KeyRect &a, const KeyRect &b) {
        return a.top != b.top ? a.top < b.top : a.left < b.left;
    });
    int mostCommonKeyWidth = 0;
    int mostCommonKeyHeight = 0;
    computeMostCommonKeySize(keys, &mostCommonKeyWidth, &mostCommonKeyHeight);

    // --- computeNearestNeighbors() ---
    const int keyCount = static_cast<int>(keys.size());
    const int gridSize = GRID_WIDTH * GRID_HEIGHT;
    const int cellWidth = (kbWidth + GRID_WIDTH - 1) / GRID_WIDTH;
    const int cellHeight = (kbHeight + GRID_HEIGHT - 1) / GRID_HEIGHT;
    const int threshold = static_cast<int>(mostCommonKeyWidth * SEARCH_DISTANCE);
    const int thresholdSquared = threshold * threshold;
    const int lastPixelXCoordinate = GRID_WIDTH * cellWidth - 1;
    const int lastPixelYCoordinate = GRID_HEIGHT * cellHeight - 1;
    const int halfCellWidth = cellWidth / 2;
    const int halfCellHeight = cellHeight / 2;
    std::vector<std::vector<int>> gridNeighbors(gridSize);
    for (int k = 0; k < keyCount; ++k) {
        const KeyRect &key = keys[k];
        const int topPixelWithinThreshold = key.top - threshold;
        const int yDeltaToGrid = topPixelWithinThreshold % cellHeight;
        const int yMiddleOfTopCell = topPixelWithinThreshold - yDeltaToGrid + halfCellHeight;
        const int yStart = std::max(halfCellHeight,
                yMiddleOfTopCell + (yDeltaToGrid <= halfCellHeight ? 0 : cellHeight));
        const int yEnd = std::min(lastPixelYCoordinate, key.top + key.height + threshold);
        const int leftPixelWithinThreshold = key.left - threshold;
        const int xDeltaToGrid = leftPixelWithinThreshold % cellWidth;
        const int xMiddleOfLeftCell = leftPixelWithinThreshold - xDeltaToGrid + halfCellWidth;
        const int xStart = std::max(halfCellWidth,
                xMiddleOfLeftCell + (xDeltaToGrid <= halfCellWidth ? 0 : cellWidth));
        const int xEnd = std::min(lastPixelXCoordinate, key.left + key.width + threshold);
        int baseIndexOfCurrentRow = (yStart / cellHeight) * GRID_WIDTH + (xStart / cellWidth);
        for (int centerY = yStart; centerY <= yEnd; centerY += cellHeight) {
            int index = baseIndexOfCurrentRow;
            for (int centerX = xStart; centerX <= xEnd; centerX += cellWidth) {
                if (index >= 0 && index < gridSize
                        && key.squaredDistanceToEdge(centerX, centerY) < thresholdSquared) {
                    gridNeighbors[index].push_back(k);
                }
                ++index;
            }
            baseIndexOfCurrentRow += GRID_WIDTH;
        }
    }

    // --- createNativeProximityInfo() ---
    std::vector<int> proximityChars(gridSize * MAX_PROXIMITY_CHARS, NOT_A_CODE);
    for (int i = 0; i < gridSize; ++i) {
        int infoIndex = i * MAX_PROXIMITY_CHARS;
        for (const int k : gridNeighbors[i]) {
            if (infoIndex >= (i + 1) * MAX_PROXIMITY_CHARS) break;
            proximityChars[infoIndex++] = keys[k].code;
        }
    }
    std::vector<int> xs, ys, widths, heights, codes;
    for (const KeyRect &key : keys) {
        xs.push_back(key.left);
        ys.push_back(key.top);
        widths.push_back(key.width);
        heights.push_back(key.height);
        codes.push_back(key.code);
    }
    return std::make_unique<ProximityInfo>(kbWidth, kbHeight, GRID_WIDTH, GRID_HEIGHT,
            mostCommonKeyWidth, mostCommonKeyHeight, proximityChars.data(),
            static_cast<int>(proximityChars.size()), keyCount, xs.data(), ys.data(), widths.data(),
            heights.data(), codes.data(), nullptr, nullptr, nullptr);
}

void appendUtf8(std::string *out, const int cp) {
    if (cp < 0x80) {
        out->push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out->push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out->push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out->push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::vector<int> decodeUtf8(const std::string &s) {
    std::vector<int> out;
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        int len = 1;
        int cp = c;
        if (c >= 0xF0) { len = 4; cp = c & 0x07; }
        else if (c >= 0xE0) { len = 3; cp = c & 0x0F; }
        else if (c >= 0xC0) { len = 2; cp = c & 0x1F; }
        for (int k = 1; k < len && i + k < s.size(); ++k) {
            cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        }
        out.push_back(cp);
        i += len;
    }
    return out;
}

struct Suggestion {
    std::string word;
    int score;
};

struct QueryResult {
    long micros = 0;
    std::vector<Suggestion> suggestions;
};

class Engine {
 public:
    explicit Engine(const char *dictPath) {
        struct stat st;
        if (stat(dictPath, &st) != 0) {
            fprintf(stderr, "cannot stat %s\n", dictPath);
            exit(1);
        }
        auto policy = DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(
                dictPath, 0, static_cast<int>(st.st_size), false /* isUpdatable */);
        if (!policy) {
            fprintf(stderr, "cannot load dictionary %s\n", dictPath);
            exit(1);
        }
        mPolicy = policy.get();
        mDictionary.reset(new Dictionary(std::move(policy)));
    }

    bool contains(const std::string &word) const {
        const std::vector<int> cps = decodeUtf8(word);
        return mPolicy->getWordId(CodePointArrayView(cps.data(), cps.size()),
                false /* forceLowerCaseSearch */) != NOT_A_WORD_ID;
    }

    // Mirrors latinime_BinaryDictionary_getSuggestions() for a gesture.
    QueryResult query(ProximityInfo *proximityInfo, std::vector<Point> points) const {
        const int inputSize = static_cast<int>(points.size());
        std::vector<int> xs, ys, times, pointerIds;
        for (const Point &p : points) {
            xs.push_back(p.x);
            ys.push_back(p.y);
            times.push_back(p.millis);
            pointerIds.push_back(p.pointerId);
        }
        std::vector<int> inputCodePoints(MAX_WORD_LENGTH, NOT_A_CODE_POINT);
        // is-gesture, full edit distance, block offensive, space-aware, locale weight x 1000
        int options[] = {1, 0, 1, 0, 1000};
        const SuggestOptions suggestOptions(options, 5);
        const NgramContext ngramContext;
        // Fresh session per query: results never depend on the previous query.
        DicTraverseSession session(nullptr, nullptr, true /* usesLargeCache */);
        SuggestionResults results(MAX_RESULTS);
        // BinaryDictionary.getSuggestions() passes NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL.
        const float weight = NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL;

        const auto start = std::chrono::steady_clock::now();
        mDictionary->getSuggestions(proximityInfo, &session, xs.data(), ys.data(), times.data(),
                pointerIds.data(), inputCodePoints.data(), inputSize, &ngramContext,
                &suggestOptions, weight, &results);
        const std::vector<SuggestedWord> sorted = results.getSortedSuggestions();
        const auto end = std::chrono::steady_clock::now();

        QueryResult out;
        out.micros = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
        for (const SuggestedWord &w : sorted) {
            std::string word;
            for (int i = 0; i < w.getCodePointCount(); ++i) appendUtf8(&word, w.getCodePoint()[i]);
            out.suggestions.push_back({word, w.getScore()});
        }
        return out;
    }

 private:
    const DictionaryStructureWithBufferPolicy *mPolicy;
    std::unique_ptr<Dictionary> mDictionary;
};

void selfTestKeys(std::vector<KeyRect> *keys, std::map<int, std::pair<int, int>> *centres) {
    struct Row { const char *letters; int offset; int y; };
    const Row rows[] = {{"qwertyuiop", 0, 20}, {"asdfghjkl", 54, 200}, {"zxcvbnm", 162, 380}};
    for (const Row &row : rows) {
        for (int i = 0; row.letters[i]; ++i) {
            const KeyRect key = {row.offset + i * 108, row.y, 108, 160, row.letters[i]};
            keys->push_back(key);
            (*centres)[key.code] = {key.left + key.width / 2, key.top + key.height / 2};
        }
    }
}

int runSelfTest(const Engine &engine) {
    std::vector<KeyRect> keys;
    std::map<int, std::pair<int, int>> centres;
    selfTestKeys(&keys, &centres);
    const std::unique_ptr<ProximityInfo> proximityInfo = buildProximityInfo(1080, 700, keys);

    const char *words[] = {"hello", "world", "keyboard", "the", "quick", "typing", "gesture",
            "android", "swipe", "people"};
    int failures = 0;
    for (const char *word : words) {
        // Polyline through the key centres: a point every 12 px, 8 ms apart.
        std::vector<Point> points;
        int millis = 0;
        auto add = [&](const double x, const double y) {
            points.push_back({static_cast<int>(std::lround(x)), static_cast<int>(std::lround(y)),
                    millis, 0});
            millis += 8;
        };
        double px = centres[word[0]].first;
        double py = centres[word[0]].second;
        add(px, py);
        for (int i = 1; word[i]; ++i) {
            const double tx = centres[word[i]].first;
            const double ty = centres[word[i]].second;
            const double dist = std::hypot(tx - px, ty - py);
            const int steps = std::max(1, static_cast<int>(std::lround(dist / 12.0)));
            for (int s = 1; s <= steps; ++s) {
                add(px + (tx - px) * s / steps, py + (ty - py) * s / steps);
            }
            px = tx;
            py = ty;
        }
        const QueryResult result = engine.query(proximityInfo.get(), points);
        std::string top3;
        bool found = false;
        for (size_t i = 0; i < result.suggestions.size() && i < 3; ++i) {
            if (i > 0) top3 += ",";
            top3 += result.suggestions[i].word;
            if (result.suggestions[i].word == word) found = true;
        }
        if (found) {
            printf("PASS %s\n", word);
        } else {
            printf("FAIL %s %s\n", word, top3.c_str());
            ++failures;
        }
    }
    return failures == 0 ? 0 : 1;
}

int runProtocol(const Engine &engine) {
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        std::istringstream header(line);
        std::string tag;
        header >> tag;
        if (tag == "Q") {
            long id;
            std::string word;
            header >> id >> word;
            printf("IN %ld %d\n", id, engine.contains(word) ? 1 : 0);
        } else if (tag == "G") {
            long id;
            int kbWidth, kbHeight, keyCount, pointCount;
            header >> id >> kbWidth >> kbHeight >> keyCount >> pointCount;
            std::vector<KeyRect> keys(keyCount);
            std::vector<Point> points(pointCount);
            for (KeyRect &key : keys) {
                std::string t;
                std::getline(std::cin, line);
                std::istringstream in(line);
                in >> t >> key.left >> key.top >> key.width >> key.height >> key.code;
            }
            for (Point &p : points) {
                std::string t;
                std::getline(std::cin, line);
                std::istringstream in(line);
                in >> t >> p.x >> p.y >> p.millis >> p.pointerId;
            }
            const std::unique_ptr<ProximityInfo> proximityInfo =
                    buildProximityInfo(kbWidth, kbHeight, keys);
            const QueryResult result = engine.query(proximityInfo.get(), points);
            printf("R %ld %ld %zu", id, result.micros, result.suggestions.size());
            for (const Suggestion &s : result.suggestions) {
                printf(" %s %d", s.word.c_str(), s.score);
            }
            printf("\n");
        } else {
            fprintf(stderr, "unknown record: %s\n", tag.c_str());
            return 2;
        }
        fflush(stdout);
    }
    return 0;
}
} // namespace

int main(int argc, char **argv) {
    const char *dictPath = nullptr;
    bool selfTest = false;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--dict" && i + 1 < argc) {
            dictPath = argv[++i];
        } else if (arg == "--selftest") {
            selfTest = true;
        } else if (arg == "--param" && i + 1 < argc) {
            const std::string nv = argv[++i];
            const size_t eq = nv.find('=');
            if (eq == std::string::npos
                    || !GestureParams::set(nv.substr(0, eq).c_str(),
                            strtof(nv.c_str() + eq + 1, nullptr))) {
                fprintf(stderr, "unknown or malformed parameter: %s\n", nv.c_str());
                return 2;
            }
        } else {
            fprintf(stderr, "usage: gesture_eval --dict <path.dict> [--selftest] "
                    "[--param NAME=VALUE ...]\n");
            return 2;
        }
    }
    if (!dictPath) {
        fprintf(stderr, "--dict is required\n");
        return 2;
    }
    const Engine engine(dictPath);
    return selfTest ? runSelfTest(engine) : runProtocol(engine);
}
