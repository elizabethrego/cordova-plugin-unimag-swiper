Cordova Unimag-Swiper Plugin
=================================

The purpose of this plugin is to allow for swiping credit cards using the IDTech UniMag II, UniMag Pro, or Shuttle mobile readers in hybrid applications.

### Supported Platforms
- __iOS__ (SDK >= 5)
- __Android__ (SDK >= 2)

### Installation
Currently, this plugin can only be installed via the Command-Line Interface.
```
cordova plugin add https://github.com/elizabethrego/cordova-plugin-unimag-swiper/
```
## Usage
The IDTech reader will be __activated__ automatically after the Cordova "deviceready" event is fired. Here, "activated" means that the plugin will be able to listen to IDTech SDK events. The IDTech SDK (hereby referred to simply as SDK) sends a wide variety of events related to reader tasks such as connection and swipe. In response to some of these events, the plugin will fire its own events that you can capture in your application.

A reader may be either __attached__ or __detached__. It is attached when it is plugged into the mobile device running your application. While the reader is activated, once a reader is attached, (or if it is already attached upon application startup) the SDK will begin a connection task with the reader. When this task begins, a __"connecting"__ event will be fired from the plugin. After the task has finished successfully, a __"connected"__ event will fire. If a connection could not be established, a __"timeout"__ event will be fired instead. Once you have captured the __"connected"__ event you can begin initiating the swipe process.

To initiate the swipe process, call the __swipe__ method on your plugin object. By default, this will be cordova.plugins.unimag.swiper. After this method has been called you can physically swipe the card. The data will be parsed and returned if valid. This will result in a __"swipe_success"__ event containing the data (it will be stringified, you will need to parse it). If the card data was invalid, or the swipe was otherwise unsuccessful (e.g., if it was crooked) you a __"swipe_error"__ event will be fired instead. 

You can also manually deactivate and activate the reader by calling the __deactivate__ or __activate__ methods on your plugin object. Once the reader has been deactivated, it will not listen to attachment/detachment and will never attempt a connection. The reader need not be attached for it to be activated successfully - if it is activated, it will automatically detect attachment/detachment and handle connection as such.

Finally, there are two settings you can configure on the reader. The first is to enable SDK logs (disabled by default). Call the __enableLogs__ method on your plugin object to set whether logs will be printed to your console. It takes a boolean parameter, true if you want them to print. The second is to set your reader type. This is not necessary, but can be helpful if you find that something isn't working by default. Call __setReaderType__ on your plugin object, passing in the appropriate string value from the following:
 - __unimag__ (for UniMag original reader)
 - __unimag_ii__ (for UniMag II reader)
 - __unimag_pro__ (for UniMag Pro reader)
 - __shuttle__ (for Shuttle reader)
 
If any other value than these is sent, the reader type will not be set.

NOTE: To use this plugin for iOS you'll need to disable bitcode. You can do this by clicking on your project in Xcode and going to Build Settings. Search for 'bitcode', and you'l see an 'Enable Bitcode' setting. Change this to 'No'. There is currently no way for me to configure this through the plugin, as far as I'm aware.

You can also include this plugin in your application to accomplish the same thing: https://github.com/akofman/cordova-plugin-disable-bitcode.

## Events
See Sample section for how exactly to capture the events listed below.

| name             | platform     | meaning                                                          | details                                                                  |
|------------------|--------------|------------------------------------------------------------------|--------------------------------------------------------------------------|
| connecting       | iOS, Android | connection task has begun                                        | none                                                                     |
| connected        | iOS, Android | connection task was successful                                   | none                                                                     |
| disconnected     | iOS, Android | reader was disconnected                                          | none                                                                     |
| timeout          | iOS, Android | connection or swipe task has timed out                           | string: message from SDK regarding timeout type                          |
| swipe_processing | iOS, Android | swipe has been received and is processing                        | none                                                                     |
| swipe_success    | iOS, Android | card data has been parsed successfully                           | string: use JSON.parse to get object of card data w/ properties card_number, expiry_month, expiry_year, first_name, last_name, & trimmedUnimagData (raw data from reader)                                                                         |
| swipe_error      | iOS, Android | card data was invalid and could not be parsed                    | none                                                                     |
| xml_error        | Android      | xml config file listing settings for devices could not be loaded | string: message from SDK regarding particular issue with XML config file |
| connection_error | iOS          | connection task was unsuccessful                                 | string: message from plugin with reason reader could not connect         |


## Sample
The sample demonstrates how to capture events and swipe a card.

```javascript
document.addEventListener("deviceready", function () { // $ionicPlatform.ready(function() {
  cordova.plugins.unimag.swiper.enableLogs(true);
  cordova.plugins.unimag.swiper.setReaderType('unimag_ii');

  var connected = false;

  var swipe = function () {
    if (connected) {
      cordova.plugins.unimag.swiper.swipe(function successCallback () {
        console.log('SUCCESS: Swipe started.');
      }, function errorCallback () {
        console.log('ERROR: Could not start swipe.');
      });
    } else console.log('ERROR: Reader is not connected.');
  }

  cordova.plugins.unimag.swiper.on('connected', function () {
    connected = true;
  });

  cordova.plugins.unimag.swiper.on('disconnected', function () {
    connected = false;
  });

  cordova.plugins.unimag.swiper.on('swipe_success', function (e) {
    var data = JSON.parse(e.detail);
    console.log('cardholder name: ' + data.first_name + ' ' + data.last_name);
    console.log('card number:' + data.card_number);
    console.log('expiration:' + data.expiry_month + '/' + data.expiry_year);
  });

  cordova.plugins.unimag.swiper.on('swipe_error', function () {
    console.log('ERROR: Could not parse card data.');
  });

  cordova.plugins.unimag.swiper.on('timeout', function (e) {
    if (connected) {
      console.log('ERROR: Swipe timed out - ' + e.detail);
    } else {
      console.log('ERROR: Connection timed out - ' + e.detail);
    }
  });

}, false); // });
```

## Contributing (please do!)

1. Fork this repo.
2. Create your own branch. (`git checkout -b my-feature`)
3. Commit your changes. (`git commit -am 'Add my awesome feature'`)
4. Push to your branch. (`git push origin my-feature`)
5. Create new pull request.


## License

This software is released under the <a href="http://opensource.org/licenses/Apache-2.0">Apache 2.0 License</a>.

© 2015-2016 Wodify. All rights reserved.
