#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs `block` inside an Obj-C `@try/@catch` so an `NSException` (e.g. the one `AVAudioEngine`'s
/// `installTapOnBus` raises on a format/hardware mismatch) becomes a Swift-catchable `NSError` instead of
/// aborting the process. Kotlin/Native installs its own C++ terminate handler, so any uncaught Obj-C
/// exception that reaches it is turned into `abort()` (ADR-0029 Phase 2) — there is no pure-Swift way to
/// catch it, hence this tiny shim.
@interface DFNExceptionCatcher : NSObject
+ (BOOL)catchException:(NS_NOESCAPE void (^)(void))block error:(NSError *_Nullable *_Nullable)error;
@end

NS_ASSUME_NONNULL_END
