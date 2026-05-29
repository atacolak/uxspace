// Named-pipe control protocol between :app (client) and :driver (server).
// Header-only, used by both ends. Architecture: docs/ARCHITECTURE.md §6.

#pragma once

#include <cstdint>

namespace uxspace::ipc {

inline constexpr wchar_t kPipeName[] = L"\\\\.\\pipe\\UxSpaceDriver";

// Bump on any wire-format change. Both ends MUST refuse mismatched versions.
inline constexpr std::uint32_t kProtocolVersion = 1;

inline constexpr std::uint8_t kMaxMonitors = 3;

enum class MessageType : std::uint32_t {
    // Requests (app -> driver)
    Ping            = 1,
    SetMonitorCount = 2,
    SetMonitorMode  = 3,

    // Responses (driver -> app)
    Pong            = 100,
    Ack             = 101,
    Nack            = 102,
};

enum class ErrorCode : std::uint32_t {
    None              = 0,
    UnknownMessage    = 1,
    VersionMismatch   = 2,
    InvalidMonitorId  = 3,
    InvalidMode       = 4,
    TooManyMonitors   = 5,
    DriverBusy        = 6,
    Internal          = 7,
};

#pragma pack(push, 1)

// Every message on the wire is Header followed by `payload_bytes` of payload.
struct Header {
    std::uint32_t protocol_version;   // == kProtocolVersion
    MessageType   type;
    std::uint32_t payload_bytes;      // size of payload that follows
    std::uint32_t request_id;         // echoed in the matching response
};
static_assert(sizeof(Header) == 16, "Header layout is wire-visible.");

struct SetMonitorCountPayload {
    std::uint8_t count;               // 0..kMaxMonitors
    std::uint8_t _pad[3];
};
static_assert(sizeof(SetMonitorCountPayload) == 4);

struct SetMonitorModePayload {
    std::uint8_t  monitor_id;         // 0..(count-1)
    std::uint8_t  _pad[3];
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t refresh_hz;
};
static_assert(sizeof(SetMonitorModePayload) == 16);

struct NackPayload {
    ErrorCode     code;
    char          message[124];       // ASCII, NUL-terminated, may be empty
};
static_assert(sizeof(NackPayload) == 128);

// Ping / Pong / Ack carry no payload.

#pragma pack(pop)

} // namespace uxspace::ipc
