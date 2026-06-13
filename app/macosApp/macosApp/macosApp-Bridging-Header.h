// Obj-C → Swift bridging header (ADR-0029 Phase 2). Exposes DFNExceptionCatcher so Swift can wrap
// AVFoundation calls that throw NSException (Kotlin/Native can't catch those — they'd abort).
#import "ExceptionCatcher.h"
