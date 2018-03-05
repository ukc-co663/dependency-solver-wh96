all: build

build: setup
	./gradlew build -q

clean: setup
	./gradlew clean -q

setup:
	./setup.sh

.PHONY: all build clean setup
