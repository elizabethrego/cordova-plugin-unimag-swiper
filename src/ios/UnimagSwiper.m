#import "UnimagSwiper.h"

/**
* This plugin allows for swiping a credit or debit card and returning
* its parsed data for use in financial transactions.
*/
@implementation UnimagSwiper

// Indicates whether app has launched and observers have been set
BOOL pluginInited = NO;

// Reader from SDK to handle all swipe functionality
uniMag* reader;

// Indicates if the containing app has not manually deactivated reader
BOOL readerActivated = NO;

// Stores user preference, default false
BOOL enableLogs = NO;

// Type of uniMag reader
UmReader readerType;


/***************************************************
* LIFECYCLE
***************************************************/


/**
* Rather than register an observer for UIApplicationDidFinishLaunchingNotification,
* this method is used directly to register observers for subsequent lifecycle
* notifications.
*/
- (void)initPlugin {
    if (!pluginInited) {
        [uniMag enableLogging:enableLogs];

        NSNotificationCenter* center = [NSNotificationCenter defaultCenter];

        [center addObserver:self
            selector:@selector(onPause:)
            name:UIApplicationDidEnterBackgroundNotification
            object: nil];

        [center addObserver:self
            selector:@selector(onResume:)
            name:UIApplicationWillEnterForegroundNotification
            object:nil];

        pluginInited = YES;
    }
}

/** 
* Called when the application enters the background. 
* The reader is killed to maintain consistency with Android behavior.
*/
- (void)onPause:(NSNotification*)notification {
    if (readerActivated) {
        [self deactivateReader:nil];
    }
}

/**
* Called when the application returns to the foreground.
* The reader is reinitialized as if the app was just opened.
*/
- (void)onResume:(NSNotification*)notification {
    if (readerActivated) {
        [self activateReader:nil];
    }
}


/***************************************************
* JAVASCRIPT INTERFACE IMPLEMENTATION
***************************************************/

/**
* Initializes uniMag objext to start listening to SDK events
* for connection, disconnection, swiping, etc.
* 
* @param {CDVInvokedUrlCommand*} 
*        The command sent from JavaScript
*/
- (void) activateReader:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        CDVPluginResult* result;

        // Register observers if this is the first time reader
        // is activated
        [self initPlugin];

        if (!reader) {
            // Because logging is a class method, we can call
            // it before initialization
            [uniMag enableLogging:enableLogs];

            // Begin listening to SDK events, including
            // initialization
            [self setReaderListener:TRUE];

            reader = [[uniMag alloc] init];
            [reader setAutoConnect:YES];
            [reader setSwipeTimeoutDuration:30];
            [reader setAutoAdjustVolume:TRUE];

            // Set type if possible
            if (readerType) {
                [reader setReaderType:readerType];
            }

            // Store status of connection task
            UmRet activated = [reader startUniMag:YES];

            if (activated == UMRET_SUCCESS || activated == UMRET_NO_READER) {
                result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
            } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                messageAsString:[NSString stringWithFormat:
                    @"Failed to activate reader: %@", [self getUmRetErrorMessage:activated]]];
        
        } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
            messageAsString:@"Reader is already activated."];

        if (command) {
            readerActivated = YES;
            [self.commandDelegate sendPluginResult:result callbackId:[command callbackId]];
        }
    }];
}

/**
* Releases uniMag object. Because this stops listening to SDK
* events, swiper will no longer function until activateReader
* is called again by the containing app, unless this is called
* by onPause.
* 
* @param {CDVInvokedUrlCommand*} command 
*        The command sent from JavaScript
*/
- (void) deactivateReader:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result;

    if (reader) {
        [reader cancelTask];

        // Stop listening to SDK events
        [self setReaderListener:FALSE];
        reader = nil;

        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self fireEvent:@"disconnected"];

    } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
        messageAsString:@"Reader is already deactivated."];

    if (command) {
        readerActivated = false;
        [self.commandDelegate sendPluginResult:result callbackId:[command callbackId]];
    }
}

/**
* Tells the SDK to begin expecting a swipe. From the moment this is
* called, the user will have 30 seconds to swipe a card before a
* timeout error occurs.
* 
* @param {CDVInvokedUrlCommand*} command 
*        The command sent from JavaScript
*/
- (void)swipe:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        CDVPluginResult* result;

        if (reader) {
            if ([reader getConnectionStatus]) {
                [reader cancelTask];

                // Store status of swipe task
                UmRet swipeStarted = [reader requestSwipe];

                if (swipeStarted == UMRET_SUCCESS) {
                    result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
                } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                    messageAsString:[NSString stringWithFormat:
                        @"Failed to start swipe: %@", [self getUmRetErrorMessage:swipeStarted]]];

            } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                messageAsString:@"Reader has been activated but is not connected."];

        } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
            messageAsString:@"Reader must be activated before starting swipe."];

        [self.commandDelegate sendPluginResult:result callbackId:[command callbackId]];
    }];
}

/**
* Turns SDK logs on or off.
* 
* @param {CDVInvokedUrlCommand*} command 
*        The command sent from JavaScript
*/
- (void)enableLogs:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result;

    if ([command.arguments count] > 0) {
        // Store preference
        enableLogs = (BOOL) [command.arguments objectAtIndex:0];

        // Apply preference now if possible, otherwise it will be
        // applied when swiper is started
        if (reader) {
            [uniMag enableLogging:enableLogs];
        }

        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
            messageAsString:[NSString stringWithFormat:
                @"Logging %@abled.", enableLogs ? @"en" : @"dis"]];
    
    } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
        messageAsString:@"Boolean 'enable' not specified."];

    [self.commandDelegate sendPluginResult:result callbackId:[command callbackId]];
}

/**
* Sets reader type as specified if valid.
* Not necessary, but could help when troubleshooting.
* 
* @param {CDVInvokedUrlCommand*} command 
*        The command sent from JavaScript
*/
- (void)setReaderType:(CDVInvokedUrlCommand*)command {
    CDVPluginResult* result;

    if ([command.arguments count] > 0) {
        NSString* type = [command.arguments objectAtIndex:0];
        NSArray* types = [NSArray arrayWithObjects:
            @"UMREADER_UNKNOWN",
            @"UMREADER_UNIMAG",
            @"UMREADER_UNIMAG_PRO",
            @"UMREADER_UNIMAG_II",
            @"UMREADER_SHUTTLE", nil];
        
        // Get type
        NSUInteger n = [types indexOfObject:type];

        // Store type
        readerType = (UmReader) n;

        if (reader) {
            [reader setReaderType:readerType];
        }

        result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
            messageAsString:[NSString stringWithFormat:
                @"Reader type set as \"%@\".", type]];
    
    } else result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
        messageAsString:@"Reader type not specified."];

    [self.commandDelegate sendPluginResult:result callbackId:[command callbackId]];
}


/***************************************************
* SDK CALLBACKS
***************************************************/

/**
* Receive notification from the SDK when the device is powering up.
* Can result in a timeout rather than an actual connection.
*/
- (void)umConnecting:(NSNotification *)notification {
    [self fireEvent:@"connecting"];
}

/**
* Receive notification from the SDK when the swiper is connected to
* the device. Swipe cannot be performed until this has been called.
*/
- (void)umConnected:(NSNotification *)notification {
    [self fireEvent:@"connected"];
}

/**
* Receive notification from the SDK when the swiper becomes disconnected
* from the device. 
*/
- (void)umDisconnected:(NSNotification *)notification {
    [self fireEvent:@"disconnected"];
}

/**
* Receive notification from the SDK when connection task has timed out.
*/
- (void)umConnectionTimeout:(NSNotification *)notification {
    [self fireEvent:@"timeout" withData:@"Connection timed out."];
}

/**
* Receive notification from SDK when system volume is too low to connect.
*/
- (void)umConnection_InsufficientPower:(NSNotification *)notification {
    [self fireEvent:@"connection_error" 
        withData:@"Volume too low. Please maximize volume before reattaching swiper."];
}

/**
* Receive notification from SDK when mono audio is enabled by system,
* blocking a connection.
*/
- (void)umConnectionMonoAudio:(NSNotification *)notification {
    [self fireEvent:@"connection_error" 
        withData:@"Mono audio is enabled. Please disable it in your iOS settings."];
}

/**
* Receive notification from the SDK when swipe task has timed out.
*/
- (void)umSwipeTimeout:(NSNotification *)notification {
    [self fireEvent:@"timeout" withData:@"Swipe timed out."];
}

/**
* Receive notification from the SDK as soon as it detects data coming from
* the swiper after requestSwipe API method is called.
*/
- (void)umSwipeProcessing:(NSNotification *)notification {
    [self fireEvent:@"swipe_processing"];
}

/** 
* Receive notification from the SDK when it cannot read a swipe (i.e., a
* crooked swipe) rather than behave as if no swipe was made.
*/
- (void)umSwipeError:(NSNotification *)notification {
    [self fireEvent:@"swipe_error"];
}

/** 
* Receive notification from the SDK when a successful swipe was read. Parses
* the raw card data and sends resulting JSON with event.
*/
- (void)umSwipeReceived:(NSNotification *)notification {    
    NSData* data = [notification object];

    NSString* cardData = [[NSString alloc] 
        initWithData:data 
        encoding:NSASCIIStringEncoding];

    NSString* parsedCardData = [self parseCardData:cardData];

    if (parsedCardData) {
        [self fireEvent:@"swipe_success" withData:parsedCardData];
    } else [self fireEvent:@"swipe_error"];
}

/***************************************************
* UTILS
***************************************************/

/**
* Adds or removes observers for SDK notifications.
* 
* @param {BOOL} listen
*        Whether to register
*/
- (void) setReaderListener:(BOOL)listen {
    NSNotificationCenter* center = [NSNotificationCenter defaultCenter];

    NSDictionary* sdkNotifs = [[NSDictionary alloc] initWithObjectsAndKeys:
        [NSValue valueWithPointer:@selector(umConnecting:)], uniMagPoweringNotification,
        [NSValue valueWithPointer:@selector(umConnected:)], uniMagDidConnectNotification,
        [NSValue valueWithPointer:@selector(umDisconnected:)], uniMagDidDisconnectNotification,
        [NSValue valueWithPointer:@selector(umConnectionTimeout:)], uniMagTimeoutNotification,
        [NSValue valueWithPointer:@selector(umConnection_InsufficientPower:)], uniMagInsufficientPowerNotification,
        [NSValue valueWithPointer:@selector(umConnectionMonoAudio:)], uniMagMonoAudioErrorNotification,
                   
        [NSValue valueWithPointer:@selector(umSwipeProcessing:)], uniMagDataProcessingNotification,
        [NSValue valueWithPointer:@selector(umSwipeReceived:)], uniMagDidReceiveDataNotification,
        [NSValue valueWithPointer:@selector(umSwipeTimeout:)], uniMagTimeoutSwipeNotification,
        [NSValue valueWithPointer:@selector(umSwipeError:)], uniMagInvalidSwipeNotification,
                   
        nil];

    NSEnumerator* notifEnum = [sdkNotifs keyEnumerator];
    NSString* key;

    while (key = [notifEnum nextObject]) {
        if (listen) {
            [center addObserver:self
                selector:[[sdkNotifs objectForKey:key] pointerValue]
                name:key
                object:nil];
        } else {
            [center removeObserver:self
                name:key
                object:nil];
        }
    }
}

/**
* Uses a regex to parse raw card data.
* @param  {NSString*} data
*         Raw card data
* @return {NSString*}
*         Stringified JSON representation of parsed card data
*/
- (NSString*)parseCardData:(NSString*)data {
    NSString* num;
    NSArray* name;
    NSString* exp;

    NSError *error = NULL;
    NSRegularExpression* cardParser = [NSRegularExpression regularExpressionWithPattern:
        @"%B(\\d+)\\^([^\\^]+)\\^(\\d{4})"
        options:0
        error:&error];

    NSArray* matches = [cardParser 
        matchesInString:data 
        options:0 
        range:NSMakeRange(0, [data length])];

    if ([matches count]) {
        num = [data substringWithRange:[[matches objectAtIndex:0] rangeAtIndex:1]];

        name = [[data substringWithRange:[[matches objectAtIndex:0] rangeAtIndex:2]] 
            componentsSeparatedByString:@"/"];

        exp = [data substringWithRange:[[matches objectAtIndex:0] rangeAtIndex:3]];

        if (num && [name count] >= 2 && name[0] && name[1] && exp) {
            NSDictionary* cardData = [[NSDictionary alloc] initWithObjectsAndKeys:
                num, @"card_number",

                [exp substringFromIndex:2], @"expiry_month",

                [exp substringToIndex:2], @"expiry_year",

                [[name objectAtIndex:1] stringByTrimmingCharactersInSet:
                    [NSCharacterSet whitespaceCharacterSet]], @"first_name",
                
                [[name objectAtIndex:0] stringByTrimmingCharactersInSet:
                   [NSCharacterSet whitespaceCharacterSet]], @"last_name",

                [[data componentsSeparatedByCharactersInSet:
                        [NSCharacterSet whitespaceAndNewlineCharacterSet]]
                    componentsJoinedByString:@""], @"trimmedUnimagData",
                
                nil];

            return [[NSString alloc] initWithData:
                        [NSJSONSerialization dataWithJSONObject:cardData 
                            options:0 
                            error:&error]
                encoding:NSUTF8StringEncoding];
        }
    }
    return nil;
}

/** 
* Retrieve error message corresponding to a particular UmRet value.
* 
* @param  {UmRet}      ret
*         Status of an SDK task
* @return {NSString*}
*         Corresponding error message
*/
- (NSString*) getUmRetErrorMessage:(UmRet) ret {
    switch (ret) {
        case UMRET_NO_READER:
            return @"No reader is attached.";
        case UMRET_NOT_CONNECTED:
            return @"Connection task must be run first.";
        case UMRET_ALREADY_CONNECTED:
            return @"Reader is already connected.";
        case UMRET_MONO_AUDIO:
            return @"Mono audio is enabled.";
        case UMRET_LOW_VOLUME:
            return @"iOS device playback volume is too low.";
        case UMRET_SDK_BUSY:
            return @"SDK is busy running another task.";
        default:
            return nil;
    }
}

/**
* Pass event to method overload.
* 
* @param {NSString*} event
*        The event name
*/
- (void)fireEvent:(NSString*)event {
    [self fireEvent:event withData:NULL];
}

/**
* Format and send event to JavaScript side.
* 
* @param {NSString*} event
*        The event name
* @param {NSString*} data
*        Details about the event
*/
- (void) fireEvent:(NSString*)event withData:(NSString*) data {
    NSString* js;
    NSString* dataArg = data ? 
    [NSString stringWithFormat: @"','%@", data] : @"";

    js = [NSString stringWithFormat: 
        @"cordova.plugins.unimag.swiper.fireEvent('%@%@');", event, dataArg];

    [self.commandDelegate evalJs:js];
}

@end