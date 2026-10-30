#pragma once
#include <cstddef>
#include <string>

// Returns the length of the longest prefix of `s` that does not end in the middle of a
// multi-byte UTF-8 character. Model tokens can split a character, so the bytes of an
// incomplete character are held back until the next token completes them.
inline size_t utf8_valid_prefix(const std::string &s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {  // continuation byte: keep looking for its lead byte
            --i;
            ++back;
            continue;
        }
        size_t need = 1;
        if (c >= 0xF0) need = 4;
        else if (c >= 0xE0) need = 3;
        else if (c >= 0xC0) need = 2;
        const size_t have = n - (i - 1);
        return have >= need ? n : (i - 1);
    }
    return n;
}
