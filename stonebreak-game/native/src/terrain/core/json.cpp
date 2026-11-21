#include "core/json.hpp"

#include <cctype>
#include <cstdlib>

namespace sbtg {

class JsonParser {
public:
    explicit JsonParser(const std::string& s) : s_(s) {}
    Json value() {
        ws();
        if (i_ >= s_.size()) fail("unexpected end");
        const char c = s_[i_];
        Json j;
        if (c == '{') {
            j.kind_ = Json::Object;
            ++i_;
            ws();
            if (peek() == '}') {
                ++i_;
                return j;
            }
            for (;;) {
                ws();
                std::string k = string();
                ws();
                expect(':');
                j.obj_[k] = value();
                ws();
                if (peek() == ',') {
                    ++i_;
                    continue;
                }
                expect('}');
                return j;
            }
        }
        if (c == '[') {
            j.kind_ = Json::Array;
            ++i_;
            ws();
            if (peek() == ']') {
                ++i_;
                return j;
            }
            for (;;) {
                j.arr_.push_back(value());
                ws();
                if (peek() == ',') {
                    ++i_;
                    continue;
                }
                expect(']');
                return j;
            }
        }
        if (c == '"') {
            j.kind_ = Json::String;
            j.text_ = string();
            return j;
        }
        if (lit("true")) {
            j.kind_ = Json::Bool;
            j.b_ = true;
            return j;
        }
        if (lit("false")) {
            j.kind_ = Json::Bool;
            return j;
        }
        if (lit("null")) return j;
        if (lit("NaN")) {
            j.kind_ = Json::Number;
            j.text_ = "nan";
            return j;
        }
        if (lit("Infinity")) {
            j.kind_ = Json::Number;
            j.text_ = "inf";
            return j;
        }
        if (lit("-Infinity")) {
            j.kind_ = Json::Number;
            j.text_ = "-inf";
            return j;
        }
        size_t st = i_;
        while (i_ < s_.size() && (std::isdigit(static_cast<unsigned char>(s_[i_])) || s_[i_] == '-' || s_[i_] == '+' ||
                                  s_[i_] == '.' || s_[i_] == 'e' || s_[i_] == 'E'))
            ++i_;
        if (st == i_) fail("bad value");
        j.kind_ = Json::Number;
        j.text_ = s_.substr(st, i_ - st);
        return j;
    }
    void end() {
        ws();
        if (i_ != s_.size()) fail("trailing text");
    }

private:
    [[noreturn]] void fail(const char* why) const {
        throw std::invalid_argument(std::string("json: ") + why + " at " + std::to_string(i_));
    }
    char peek() const { return i_ < s_.size() ? s_[i_] : '\0'; }
    void ws() {
        while (i_ < s_.size() && std::isspace(static_cast<unsigned char>(s_[i_]))) ++i_;
    }
    void expect(char c) {
        if (peek() != c) fail("unexpected character");
        ++i_;
    }
    bool lit(const char* w) {
        size_t n = std::char_traits<char>::length(w);
        if (s_.compare(i_, n, w) == 0) {
            i_ += n;
            return true;
        }
        return false;
    }
    std::string string() {
        expect('"');
        std::string out;
        while (i_ < s_.size() && s_[i_] != '"') {
            char c = s_[i_++];
            if (c == '\\') {
                if (i_ >= s_.size()) fail("bad escape");
                char e = s_[i_++];
                switch (e) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'u': {
                    if (i_ + 4 > s_.size()) fail("bad \\u");
                    unsigned v = static_cast<unsigned>(std::stoul(s_.substr(i_, 4), nullptr, 16));
                    i_ += 4;
                    if (v < 0x80) {
                        out += static_cast<char>(v);
                    } else if (v < 0x800) {
                        out += static_cast<char>(0xC0 | (v >> 6));
                        out += static_cast<char>(0x80 | (v & 0x3F));
                    } else {
                        out += static_cast<char>(0xE0 | (v >> 12));
                        out += static_cast<char>(0x80 | ((v >> 6) & 0x3F));
                        out += static_cast<char>(0x80 | (v & 0x3F));
                    }
                    break;
                }
                default: out += e;
                }
            } else {
                out += c;
            }
        }
        expect('"');
        return out;
    }
    const std::string& s_;
    size_t i_ = 0;
};

Json Json::parse(const std::string& text) {
    JsonParser p(text);
    Json j = p.value();
    p.end();
    return j;
}

const Json& Json::operator[](const std::string& k) const {
    if (kind_ != Object) throw std::invalid_argument("json: not an object (looking up '" + k + "')");
    auto it = obj_.find(k);
    if (it == obj_.end()) throw std::out_of_range("json: no key '" + k + "'");
    return it->second;
}
const Json& Json::operator[](size_t i) const {
    if (kind_ != Array || i >= arr_.size()) throw std::out_of_range("json: index");
    return arr_[i];
}
const std::vector<Json>& Json::items() const {
    if (kind_ != Array) throw std::invalid_argument("json: not an array");
    return arr_;
}
const std::map<std::string, Json>& Json::members() const {
    if (kind_ != Object) throw std::invalid_argument("json: not an object");
    return obj_;
}
double Json::num() const {
    if (kind_ == Bool) return b_ ? 1.0 : 0.0;
    if (kind_ != Number) throw std::invalid_argument("json: not a number");
    return std::strtod(text_.c_str(), nullptr);
}
float Json::f32() const {
    if (kind_ == Bool) return b_ ? 1.0f : 0.0f;
    if (kind_ != Number) throw std::invalid_argument("json: not a number");
    return std::strtof(text_.c_str(), nullptr);
}
int64_t Json::i64() const {
    if (kind_ == Bool) return b_ ? 1 : 0;
    if (kind_ != Number) throw std::invalid_argument("json: not a number");
    if (text_.find_first_of(".eE") != std::string::npos) return static_cast<int64_t>(num());
    return std::strtoll(text_.c_str(), nullptr, 10);
}
bool Json::boolean() const {
    if (kind_ == Number) return num() != 0.0;
    if (kind_ != Bool) throw std::invalid_argument("json: not a bool");
    return b_;
}
const std::string& Json::str() const {
    if (kind_ != String) throw std::invalid_argument("json: not a string");
    return text_;
}
std::vector<float> Json::f32s() const {
    std::vector<float> v;
    for (const Json& e : items()) v.push_back(e.f32());
    return v;
}
std::vector<int64_t> Json::i64s() const {
    std::vector<int64_t> v;
    for (const Json& e : items()) v.push_back(e.i64());
    return v;
}

} // namespace sbtg
