#import <Cordova/CDVPlugin.h>
#import "uniMag.h"

@interface UnimagSwiper : CDVPlugin 

- (void) activateReader:(CDVInvokedUrlCommand*)command;

- (void) deactivateReader:(CDVInvokedUrlCommand*)command;

- (void)swipe:(CDVInvokedUrlCommand*)command;

- (void)enableLogs:(CDVInvokedUrlCommand*)command;

- (void)setReaderType:(CDVInvokedUrlCommand*)command;

@end