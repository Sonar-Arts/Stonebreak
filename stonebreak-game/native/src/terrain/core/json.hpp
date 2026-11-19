// Minimal JSON reader for the native manifest (export/native_manifest.py) and call parameters. Numbers keep their
// text, so a float32 written with repr() round-trips exactly (stof of the shortest repr == the fp32 value).
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace sbtg {

class Json {
public:
    enum Kind { Null, Bool, Number, String, Array, Object };

    static Json parse(const std::string& text);

    Kind kind() const { return kind_; }
    bool is_null() const { return kind_ == Null; }
    bool has(const std::string& k) const { return kind_ == Object && obj_.count(k) != 0; }
    const Json& operator[](const std::string& k) const;
    const Json& operator[](size_t i) const;
    size_t size() const { return kind_ == Array ? arr_.size() : kind_ == Object ? obj_.size() : 0; }
    const std::vector<Json>& items() const;
    const std::map<std::string, Json>& members() const;

    double num() const;
    float f32() const;                  // the number's text parsed as float (exact for repr() of an fp32)
    int64_t i64() const;
    int i32() const { return static_cast<int>(i64()); }
    bool boolean() const;
    const std::string& str() const;
    const std::string& text() const { return text_; }   // number text

    double num_or(const std::string& k, double d) const { return has(k) ? (*this)[k].num() : d; }
    int64_t i64_or(const std::string& k, int64_t d) const { return has(k) ? (*this)[k].i64() : d; }
    std::string str_or(const std::string& k, const std::string& d) const { return has(k) ? (*this)[k].str() : d; }
    bool bool_or(const std::string& k, bool d) const { return has(k) ? (*this)[k].boolean() : d; }
    std::vector<float> f32s() const;
    std::vector<int64_t> i64s() const;

private:
    friend class JsonParser;
    Kind kind_ = Null;
    bool b_ = false;
    std::string text_;
    std::vector<Json> arr_;
    std::map<std::string, Json> obj_;
};

} // namespace sbtg
