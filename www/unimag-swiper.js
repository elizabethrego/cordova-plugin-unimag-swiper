var exec = require('cordova/exec'),
	channel = require('cordova/channel');

var activate = function(success, error) {
	exec(success, error, 'UnimagSwiper', 'activateReader', []);
};

var getReaderTypes = function() {
	if (device.platform == 'Android') {
		return {
			unimag: 'UM',
			unimag_pro: 'UM_PRO',
			unimag_ii: 'UM_II',
			shuttle: 'SHUTTLE'
		};
	} else {
		return {
			unimag: 'UMREADER_UNIMAG',
			unimag_pro: 'UMREADER_UNIMAG_PRO',
			unimag_ii: 'UMREADER_UNIMAG_II',
			shuttle:'UMREADER_SHUTTLE'
		};
	}
};

var Swiper = function() {};

Swiper.activate = function(success, error) {
	activate(success, error);
};

Swiper.deactivate = function(success, error) {
	exec(success, error, 'UnimagSwiper', 'deactivateReader', []);
};

Swiper.swipe = function (success, error) {
	exec(success, error, 'UnimagSwiper', 'swipe', []);
};

Swiper.enableLogs = function (enable, success, error) {
	exec(success, error, 'UnimagSwiper', 'enableLogs', [enable]);
};

Swiper.setReaderType = function (type, success, error) {
	var readerType = getReaderTypes()[type];
	if (readerType) {
		exec(success, error, 'UnimagSwiper', 'setReaderType', [readerType]);
	} else console.log('Could not set reader type - invalid type "' + type + '" provided.');
};

Swiper.autoConfig = function (success, error) {
	if (device.platform == 'Android') {
		exec(success, error, 'UnimagSwiper', 'autoConfig', []);
	}
};

Swiper.fireEvent = function (event, data) {
	var customEvent = new CustomEvent(event, { 'detail': data} );
	window.dispatchEvent(customEvent);
};

Swiper.on = function (event, callback, scope) {
	window.addEventListener(event, callback.bind(scope || window));
};

channel.deviceready.subscribe(function () {
	var success = function () {
		console.log('Unimag Swiper initialized.');
	};

	var error = function (error) {
		console.log('Unimag Swiper could not be initialized - ' + error);
	}

	activate(success, error);
});

module.exports = Swiper;