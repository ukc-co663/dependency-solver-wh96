all: build

build: 
	./gradlew build -q

clean: 
	./gradlew clean -q

.PHONY: all build clean
