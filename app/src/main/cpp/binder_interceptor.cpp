// Copyright 2025 Dakkshesh <beakthoven@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

#include <android/binder.h>
#include <binder/Binder.h>
#include <binder/Common.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <sys/ioctl.h>
#include <utils/StrongPointer.h>

#include <map>
#include <memory>
#include <mutex>
#include <queue>
#include <shared_mutex>
#include <span>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

#include "logging.hpp"
#include "lsplt.hpp"

using namespace android;

namespace {
namespace intercept_constants {
constexpr uint32_t kRegisterInterceptor = 1;
constexpr uint32_t kUnregisterInterceptor = 2;

constexpr uint32_t kPreTransact = 1;
constexpr uint32_t kPostTransact = 2;

constexpr uint32_t kActionSkip = 1;
constexpr uint32_t kActionContinue = 2;
constexpr uint32_t kActionOverrideReply = 3;
constexpr uint32_t kActionOverrideData = 4;

constexpr uint32_t kBackdoorCode = 0xdeadbeef;
} // namespace intercept_constants
} // namespace

class BinderInterceptor : public BBinder {
    struct InterceptorRegistration {
        wp<IBinder> target_binder{};
        sp<IBinder> interceptor_binder;

        InterceptorRegistration() = default;
        InterceptorRegistration(wp<IBinder> target, sp<IBinder> interceptor)
            : target_binder(std::move(target)), interceptor_binder(std::move(interceptor)) {}
    };
    using RwLock = std::shared_mutex;
    using WriteGuard = std::unique_lock<RwLock>;
    using ReadGuard = std::shared_lock<RwLock>;

    mutable RwLock interceptor_registry_lock_;
    std::map<wp<IBinder>, InterceptorRegistration> interceptor_registry_{};

public:
    status_t onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply, uint32_t flags) override;

    bool handleInterceptedTransaction(sp<BBinder> target_binder, uint32_t transaction_code, const Parcel &request_data,
                                      Parcel *reply_data, uint32_t transaction_flags, status_t &result);

    bool shouldInterceptBinder(const wp<BBinder> &target_binder) const;

private:
    status_t handleRegisterInterceptor(const android::Parcel &data);
    status_t handleUnregisterInterceptor(const android::Parcel &data);

    template <typename ParcelWriter>
    status_t writeInterceptorCallData(ParcelWriter &writer, sp<BBinder> target_binder, uint32_t transaction_code,
                                      uint32_t transaction_flags, const Parcel &data) const;

    status_t validateInterceptorResponse(const Parcel &response, int32_t &action_type) const;
};

static sp<BinderInterceptor> g_binder_interceptor = nullptr;

struct ThreadTransactionInfo {
    uint32_t transaction_code;
    wp<BBinder> target_binder;

    ThreadTransactionInfo() = default;
    ThreadTransactionInfo(uint32_t code, wp<BBinder> target) : transaction_code(code), target_binder(std::move(target)) {}
};

// A thread-safe map to hold a queue of transactions for each thread.
static std::mutex g_thread_transaction_map_mutex;
static std::map<std::thread::id, std::queue<ThreadTransactionInfo>> g_thread_transaction_map;

class BinderStub : public BBinder {
    status_t onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply, uint32_t flags) override {
        LOGD("BinderStub transaction: %u", code);

        ThreadTransactionInfo transaction_info;
        bool has_info = false;

        { // Start a new scope for the lock guard
            std::lock_guard<std::mutex> lock(g_thread_transaction_map_mutex);
            auto thread_id = std::this_thread::get_id();
            auto it = g_thread_transaction_map.find(thread_id);

            if (it != g_thread_transaction_map.end() && !it->second.empty()) {
                // Found a queue for this thread with items in it.
                transaction_info = std::move(it->second.front());
                it->second.pop();
                has_info = true;

                // Clean up: if the queue is now empty, remove its entry from the map
                // to prevent the map from growing indefinitely over time.
                if (it->second.empty()) {
                    g_thread_transaction_map.erase(it);
                }
            }
        } // The lock is released here

        if (!has_info) {
            LOGW("No pending transaction info for stub");
            return UNKNOWN_TRANSACTION;
        }

        if (transaction_info.target_binder == nullptr && transaction_info.transaction_code == intercept_constants::kBackdoorCode &&
            reply != nullptr) {
            LOGD("Backdoor access requested - providing interceptor reference");
            reply->writeStrongBinder(g_binder_interceptor);
            return OK;
        }

        if (auto promoted_target = transaction_info.target_binder.promote()) {
            LOGD("Processing intercepted transaction");
            status_t result;
            if (!g_binder_interceptor->handleInterceptedTransaction(promoted_target, transaction_info.transaction_code, data, reply,
                                                                    flags, result)) {
                LOGD("Forwarding to original binder");
                result = promoted_target->transact(transaction_info.transaction_code, data, reply, flags);
            }
            return result;
        } else {
            LOGE("Failed to promote weak reference to target binder");
            return DEAD_OBJECT;
        }
    }
};

static sp<BinderStub> g_binder_stub = nullptr;

int (*original_ioctl_function)(int fd, int request, ...) = nullptr;

namespace {
bool processBinderTransaction(binder_transaction_data *transaction_data) {
    if (!transaction_data || transaction_data->target.ptr == 0) {
        return false;
    }

    bool should_intercept = false;
    ThreadTransactionInfo transaction_info{};

    if (transaction_data->code == intercept_constants::kBackdoorCode && transaction_data->sender_euid == 0) {
        transaction_info.transaction_code = intercept_constants::kBackdoorCode;
        transaction_info.target_binder = nullptr;
        should_intercept = true;
        LOGD("Backdoor transaction detected from root user");
    } else {
        auto *weak_ref = reinterpret_cast<RefBase::weakref_type *>(transaction_data->target.ptr);
        if (weak_ref->attemptIncStrong(nullptr)) {
            auto *target_binder = reinterpret_cast<BBinder *>(transaction_data->cookie);
            auto weak_binder = wp<BBinder>::fromExisting(target_binder);

            if (g_binder_interceptor->shouldInterceptBinder(weak_binder)) {
                transaction_info.transaction_code = transaction_data->code;
                transaction_info.target_binder = weak_binder;
                should_intercept = true;
                LOGD("Interception required for transaction code=%u target=%p", transaction_data->code, target_binder);
            }
            target_binder->decStrong(nullptr);
        }
    }

    if (should_intercept) {
        LOGD("Redirecting transaction through stub");
        transaction_data->target.ptr = reinterpret_cast<uintptr_t>(g_binder_stub->getWeakRefs());
        transaction_data->cookie = reinterpret_cast<uintptr_t>(g_binder_stub.get());
        transaction_data->code = intercept_constants::kBackdoorCode;
        // Get the queue for the current thread (or create it if it doesn't exist)
        // and push the transaction info.
        g_thread_transaction_map[std::this_thread::get_id()].push(std::move(transaction_info));
    }

    return should_intercept;
}

void processBinderWriteRead(const binder_write_read &write_read_data) {
    if (write_read_data.read_buffer == 0 || write_read_data.read_size == 0 || write_read_data.read_consumed <= sizeof(uint32_t)) {
        return;
    }

    LOGD("Processing binder read buffer: ptr=%p size=%llu consumed=%llu", reinterpret_cast<void *>(write_read_data.read_buffer),
         write_read_data.read_size, write_read_data.read_consumed);

    auto buffer_ptr = write_read_data.read_buffer;
    auto remaining_bytes = write_read_data.read_consumed;

    while (remaining_bytes > 0) {
        if (remaining_bytes < sizeof(uint32_t)) {
            LOGE("Insufficient bytes for command header: %llu", static_cast<unsigned long long>(remaining_bytes));
            break;
        }

        auto command = *reinterpret_cast<const uint32_t *>(buffer_ptr);
        buffer_ptr += sizeof(uint32_t);
        remaining_bytes -= sizeof(uint32_t);

        auto command_size = _IOC_SIZE(command);
        LOGD("Processing binder command: %u (size: %u)", command, command_size);

        if (remaining_bytes < command_size) {
            LOGE("Insufficient bytes for command data: %llu < %u", static_cast<unsigned long long>(remaining_bytes), command_size);
            break;
        }

        if (command == BR_TRANSACTION_SEC_CTX || command == BR_TRANSACTION) {
            binder_transaction_data *transaction_data = nullptr;

            if (command == BR_TRANSACTION_SEC_CTX) {
                LOGD("Processing BR_TRANSACTION_SEC_CTX");
                auto *secctx_data = reinterpret_cast<const binder_transaction_data_secctx *>(buffer_ptr);
                transaction_data = const_cast<binder_transaction_data *>(&secctx_data->transaction_data);
            } else {
                LOGD("Processing BR_TRANSACTION");
                transaction_data = reinterpret_cast<binder_transaction_data *>(buffer_ptr);
            }

            if (transaction_data) {
                processBinderTransaction(transaction_data);
            } else {
                LOGE("Failed to extract transaction data");
            }
        }

        buffer_ptr += command_size;
        remaining_bytes -= command_size;
    }
}
} // namespace

int intercepted_ioctl_function(int fd, int request, ...) {
    va_list args;
    va_start(args, request);
    auto *argument = va_arg(args, void *);
    va_end(args);

    auto result = original_ioctl_function(fd, request, argument);

    if (result >= 0 && request == BINDER_WRITE_READ && argument) {
        const auto &write_read_data = *static_cast<const binder_write_read *>(argument);
        processBinderWriteRead(write_read_data);
    }

    return result;
}

bool BinderInterceptor::shouldInterceptBinder(const wp<BBinder> &target_binder) const {
    ReadGuard guard{interceptor_registry_lock_};
    return interceptor_registry_.find(target_binder) != interceptor_registry_.end();
}

status_t BinderInterceptor::onTransact(uint32_t code, const android::Parcel &data, android::Parcel *reply, uint32_t flags) {
    switch (code) {
    case intercept_constants::kRegisterInterceptor:
        return handleRegisterInterceptor(data);
    case intercept_constants::kUnregisterInterceptor:
        return handleUnregisterInterceptor(data);
    default:
        return UNKNOWN_TRANSACTION;
    }
}

status_t BinderInterceptor::handleRegisterInterceptor(const android::Parcel &data) {
    sp<IBinder> target_binder, interceptor_binder;

    if (data.readStrongBinder(&target_binder) != OK) {
        LOGE("Failed to read target binder from registration data");
        return BAD_VALUE;
    }

    if (!target_binder->localBinder()) {
        LOGE("Target binder is not a local binder");
        return BAD_VALUE;
    }

    if (data.readStrongBinder(&interceptor_binder) != OK) {
        LOGE("Failed to read interceptor binder from registration data");
        return BAD_VALUE;
    }

    {
        WriteGuard write_guard{interceptor_registry_lock_};
        wp<IBinder> weak_target = target_binder;

        auto iterator = interceptor_registry_.lower_bound(weak_target);
        if (iterator == interceptor_registry_.end() || iterator->first != weak_target) {
            iterator =
                interceptor_registry_.emplace_hint(iterator, weak_target, InterceptorRegistration{weak_target, interceptor_binder});
        } else {
            iterator->second.interceptor_binder = interceptor_binder;
        }

        LOGI("Registered interceptor for binder %p", target_binder.get());
        return OK;
    }
}

status_t BinderInterceptor::handleUnregisterInterceptor(const android::Parcel &data) {
    sp<IBinder> target_binder, interceptor_binder;

    if (data.readStrongBinder(&target_binder) != OK) {
        LOGE("Failed to read target binder from unregistration data");
        return BAD_VALUE;
    }

    if (!target_binder->localBinder()) {
        LOGE("Target binder is not a local binder");
        return BAD_VALUE;
    }

    if (data.readStrongBinder(&interceptor_binder) != OK) {
        LOGE("Failed to read interceptor binder from unregistration data");
        return BAD_VALUE;
    }

    {
        WriteGuard write_guard{interceptor_registry_lock_};
        wp<IBinder> weak_target = target_binder;

        auto iterator = interceptor_registry_.find(weak_target);
        if (iterator != interceptor_registry_.end()) {
            if (iterator->second.interceptor_binder != interceptor_binder) {
                LOGE("Interceptor mismatch during unregistration");
                return BAD_VALUE;
            }
            interceptor_registry_.erase(iterator);
            LOGI("Unregistered interceptor for binder %p", target_binder.get());
            return OK;
        }

        LOGW("Attempted to unregister non-existent interceptor");
        return BAD_VALUE;
    }
}

bool BinderInterceptor::handleInterceptedTransaction(sp<BBinder> target_binder, uint32_t transaction_code, const Parcel &request_data,
                                                     Parcel *reply_data, uint32_t transaction_flags, status_t &result) {
#define VALIDATE_STATUS(expr)                                   \
    do {                                                        \
        auto __result = (expr);                                 \
        if (__result != OK) {                                   \
            LOGE("Operation failed: " #expr " = %d", __result); \
            return false;                                       \
        }                                                       \
    } while (0)

    sp<IBinder> interceptor_binder;
    {
        ReadGuard read_guard{interceptor_registry_lock_};
        auto iterator = interceptor_registry_.find(target_binder);
        if (iterator == interceptor_registry_.end()) {
            LOGE("No interceptor found for target binder %p", target_binder.get());
            return false;
        }
        interceptor_binder = iterator->second.interceptor_binder;
    }

    LOGD("Intercepting transaction: binder=%p code=%u flags=%u reply=%s", target_binder.get(), transaction_code, transaction_flags,
         reply_data ? "true" : "false");

    Parcel pre_request_data, pre_response_data, modified_request_data;

    VALIDATE_STATUS(writeInterceptorCallData(pre_request_data, target_binder, transaction_code, transaction_flags, request_data));
    VALIDATE_STATUS(interceptor_binder->transact(intercept_constants::kPreTransact, pre_request_data, &pre_response_data));

    int32_t pre_action_type;
    VALIDATE_STATUS(validateInterceptorResponse(pre_response_data, pre_action_type));

    LOGD("Pre-transaction action type: %d", pre_action_type);

    switch (pre_action_type) {
    case intercept_constants::kActionSkip:
        return false;

    case intercept_constants::kActionOverrideReply:
        result = pre_response_data.readInt32();
        if (reply_data) {
            size_t reply_size = pre_response_data.readUint64();
            VALIDATE_STATUS(reply_data->appendFrom(&pre_response_data, pre_response_data.dataPosition(), reply_size));
        }
        return true;

    case intercept_constants::kActionOverrideData: {
        size_t data_size = pre_response_data.readUint64();
        VALIDATE_STATUS(modified_request_data.appendFrom(&pre_response_data, pre_response_data.dataPosition(), data_size));
        break;
    }

    case intercept_constants::kActionContinue:
    default:
        VALIDATE_STATUS(modified_request_data.appendFrom(&request_data, 0, request_data.dataSize()));
        break;
    }

    result = target_binder->transact(transaction_code, modified_request_data, reply_data, transaction_flags);

    Parcel post_request_data, post_response_data;

    VALIDATE_STATUS(post_request_data.writeStrongBinder(target_binder));
    VALIDATE_STATUS(post_request_data.writeUint32(transaction_code));
    VALIDATE_STATUS(post_request_data.writeUint32(transaction_flags));
    VALIDATE_STATUS(post_request_data.writeInt32(IPCThreadState::self()->getCallingUid()));
    VALIDATE_STATUS(post_request_data.writeInt32(IPCThreadState::self()->getCallingPid()));
    VALIDATE_STATUS(post_request_data.writeInt32(result));
    VALIDATE_STATUS(post_request_data.writeUint64(request_data.dataSize()));
    VALIDATE_STATUS(post_request_data.appendFrom(&request_data, 0, request_data.dataSize()));

    size_t reply_size = reply_data ? reply_data->dataSize() : 0;
    VALIDATE_STATUS(post_request_data.writeUint64(reply_size));
    LOGD("Transaction sizes: request=%zu reply=%zu", request_data.dataSize(), reply_size);

    if (reply_data && reply_size > 0) {
        VALIDATE_STATUS(post_request_data.appendFrom(reply_data, 0, reply_size));
    }

    VALIDATE_STATUS(interceptor_binder->transact(intercept_constants::kPostTransact, post_request_data, &post_response_data));

    int32_t post_action_type;
    VALIDATE_STATUS(validateInterceptorResponse(post_response_data, post_action_type));

    LOGD("Post-transaction action type: %d", post_action_type);

    if (post_action_type == intercept_constants::kActionOverrideReply) {
        result = post_response_data.readInt32();
        if (reply_data) {
            size_t new_reply_size = post_response_data.readUint64();
            reply_data->freeData();
            VALIDATE_STATUS(reply_data->appendFrom(&post_response_data, post_response_data.dataPosition(), new_reply_size));
            LOGD("Reply overridden: original_size=%zu new_size=%zu", reply_size, new_reply_size);
        }
    }

    return true;

#undef VALIDATE_STATUS
}

template <typename ParcelWriter>
status_t BinderInterceptor::writeInterceptorCallData(ParcelWriter &writer, sp<BBinder> target_binder, uint32_t transaction_code,
                                                     uint32_t transaction_flags, const Parcel &data) const {
    auto status = writer.writeStrongBinder(target_binder);
    if (status != OK)
        return status;

    status = writer.writeUint32(transaction_code);
    if (status != OK)
        return status;

    status = writer.writeUint32(transaction_flags);
    if (status != OK)
        return status;

    status = writer.writeInt32(IPCThreadState::self()->getCallingUid());
    if (status != OK)
        return status;

    status = writer.writeInt32(IPCThreadState::self()->getCallingPid());
    if (status != OK)
        return status;

    status = writer.writeUint64(data.dataSize());
    if (status != OK)
        return status;

    return writer.appendFrom(&data, 0, data.dataSize());
}

status_t BinderInterceptor::validateInterceptorResponse(const Parcel &response, int32_t &action_type) const {
    auto status = response.readInt32(&action_type);
    if (status != OK) {
        LOGE("Failed to read action type from interceptor response");
        return status;
    }

    switch (action_type) {
    case intercept_constants::kActionSkip:
    case intercept_constants::kActionContinue:
    case intercept_constants::kActionOverrideReply:
    case intercept_constants::kActionOverrideData:
        return OK;
    default:
        LOGE("Invalid action type from interceptor: %d", action_type);
        return BAD_VALUE;
    }
}

namespace {
constexpr std::string_view kBinderLibraryName = "/libbinder.so";
constexpr std::string_view kIoctlFunctionName = "ioctl";
} // namespace

bool initializeBinderInterception() {
    auto memory_maps = lsplt::MapInfo::Scan();

    dev_t binder_device_id;
    ino_t binder_inode;
    bool binder_library_found = false;

    for (const auto &memory_map : memory_maps) {
        if (memory_map.path.ends_with(kBinderLibraryName)) {
            binder_device_id = memory_map.dev;
            binder_inode = memory_map.inode;
            binder_library_found = true;
            LOGD("Found binder library: %s (dev=0x%lx, inode=%lu)", memory_map.path.c_str(),
                 static_cast<unsigned long>(binder_device_id), static_cast<unsigned long>(binder_inode));
            break;
        }
    }

    if (!binder_library_found) {
        LOGE("Failed to locate libbinder.so in process memory maps");
        return false;
    }

    g_binder_interceptor = sp<BinderInterceptor>::make();
    g_binder_stub = sp<BinderStub>::make();

    if (!g_binder_interceptor || !g_binder_stub) {
        LOGE("Failed to create binder interceptor components");
        return false;
    }

    lsplt::RegisterHook(binder_device_id, binder_inode, kIoctlFunctionName.data(),
                        reinterpret_cast<void *>(intercepted_ioctl_function), reinterpret_cast<void **>(&original_ioctl_function));

    if (!lsplt::CommitHook()) {
        LOGE("Failed to commit binder ioctl hook");
        g_binder_interceptor.clear();
        g_binder_stub.clear();
        return false;
    }

    LOGI("Binder interception initialized successfully");
    return true;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
bool entry(void *library_handle) {
    LOGI("TrickyStore binder interceptor loaded (handle: %p)", library_handle);

    bool success = initializeBinderInterception();
    if (success) {
        LOGI("Binder interception entry point completed successfully");
    } else {
        LOGE("Binder interception initialization failed");
    }

    return success;
}
