all:
	ant clean
	ant release
	jarsigner -verbose -keystore ~/.android/my-release-key.keystore bin/SystemLog-unsigned.apk mhf
	zipalign -v 4 bin/SystemLog-unsigned.apk bin/SystemLog.apk 

clean: 
	ant clean


