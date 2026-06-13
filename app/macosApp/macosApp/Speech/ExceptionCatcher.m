#import "ExceptionCatcher.h"

@implementation DFNExceptionCatcher
+ (BOOL)catchException:(NS_NOESCAPE void (^)(void))block error:(NSError *_Nullable *_Nullable)error {
    @try {
        block();
        return YES;
    } @catch (NSException *exception) {
        if (error) {
            NSString *reason = exception.reason ?: exception.name;
            *error = [NSError errorWithDomain:@"DefernoDictation"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey: reason}];
        }
        return NO;
    }
}
@end
