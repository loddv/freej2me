CXX ?= g++
CXXFLAGS ?= -O2
SDL3_CFLAGS := $(shell pkg-config --cflags sdl3)
SDL3_LIBS := $(shell pkg-config --libs sdl3)

.PHONY: all install clean

all: sdl3_interface

sdl3_interface: anbu.cpp
	$(CXX) -std=c++11 $(CXXFLAGS) $(SDL3_CFLAGS) -o $@ $< $(SDL3_LIBS) -pthread -lfreeimage

install: sdl3_interface
	@echo "Installing interface to /usr/local/bin/"
	@install -m 755 ./sdl3_interface /usr/local/bin/sdl3_interface

clean:
	rm -f ./sdl3_interface
