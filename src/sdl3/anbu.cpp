/*
Anbu, an interface between FreeJ2ME emulator and SDL3.

This file is part of FreeJ2ME.

FreeJ2ME is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

FreeJ2ME is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <FreeImage.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <cstring>
#include <iostream>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <poll.h>
#include <unistd.h>

#define DEADZONE 23000
#define BYTES 3

using namespace std;

int angle = 0;
int source_width = 0, source_height = 0;
int display_width = 0, display_height = 0;
double additional_scale = 1;
double overlay_scale = 1;
Uint64 last_time = 0;

atomic_bool capturing(true);
string interpol = "nearest";

SDL_Renderer *mRenderer = nullptr;
SDL_Texture *mBackground = nullptr;
SDL_Texture *mOverlay = nullptr;
SDL_Texture *mTexture = nullptr;
SDL_Window *mWindow = nullptr;

map<SDL_JoystickID, SDL_Joystick *> mJoysticks;
map<SDL_JoystickID, vector<int>> mPrevAxisValues;
mutex frameMutex;
vector<unsigned char> latestFrame;
bool frameReady = false;
atomic_bool frameInputDone(false);

string getHelp()
{
	return "FreeJ2ME SDL3 interface.\n"
	       "Usage: sdl3_interface width height [OPTION]...\n\n"
	       "Options:\n"
	       "  -i type               Interpolation {'nearest', 'linear', 'best'}\n"
	       "  -r angle              Rotate incoming frame by angle in degrees [0-360]\n"
	       "  -s scale              Additional scaling [0-1.0] of incoming frame\n"
	       "  -c R G B              Background color in RGB format [0-255]\n"
	       "  -b bg.png | bg.jpg    Background image";
}

void addJoystick(SDL_JoystickID deviceId)
{
	if (mJoysticks.find(deviceId) != mJoysticks.end())
	{
		return;
	}

	SDL_Joystick *joy = SDL_OpenJoystick(deviceId);
	if (joy == nullptr)
	{
		cerr << "Unable to open joystick: " << SDL_GetError() << endl;
		return;
	}

	SDL_JoystickID joystickId = SDL_GetJoystickID(joy);
	if (mJoysticks.find(joystickId) != mJoysticks.end())
	{
		SDL_CloseJoystick(joy);
		return;
	}
	int numAxes = SDL_GetNumJoystickAxes(joy);
	if (numAxes < 0)
	{
		cerr << "Unable to query joystick axes: " << SDL_GetError() << endl;
		SDL_CloseJoystick(joy);
		return;
	}

	mJoysticks[joystickId] = joy;
	mPrevAxisValues[joystickId] = vector<int>(numAxes, 0);
}

void removeJoystick(SDL_JoystickID joystickId)
{
	auto joyIt = mJoysticks.find(joystickId);
	if (joyIt == mJoysticks.end())
	{
		return;
	}

	SDL_CloseJoystick(joyIt->second);
	mJoysticks.erase(joyIt);
	mPrevAxisValues.erase(joystickId);
}

void sendKey(int key, bool pressed, bool joystick)
{
	unsigned char bytes[5];
	bytes[0] = static_cast<unsigned char>(((joystick ? 2 : 0) << 1) | (pressed ? 1 : 0));
	bytes[1] = static_cast<unsigned char>(key >> 24);
	bytes[2] = static_cast<unsigned char>(key >> 16);
	bytes[3] = static_cast<unsigned char>(key >> 8);
	bytes[4] = static_cast<unsigned char>(key);
	fwrite(bytes, sizeof(char), sizeof(bytes), stdout);
}

SDL_FRect getDestinationRect()
{
	double scale;
	switch (angle)
	{
	case 0:
	case 180:
		scale = min(static_cast<double>(display_width) / source_width,
		            static_cast<double>(display_height) / source_height);
		break;
	case 90:
	case 270:
		scale = min(static_cast<double>(display_width) / source_height,
		            static_cast<double>(display_height) / source_width);
		break;
	default:
		double angleRadians = std::acos(-1) * angle / 180;
		double boundWidth = fabs(cos(angleRadians) * source_width) + fabs(sin(angleRadians) * source_height);
		double boundHeight = fabs(sin(angleRadians) * source_width) + fabs(cos(angleRadians) * source_height);
		scale = min(display_width / boundWidth, display_height / boundHeight);
		break;
	}

	float width = static_cast<float>(source_width * scale * additional_scale);
	float height = static_cast<float>(source_height * scale * additional_scale);
	return { (display_width - width) / 2, (display_height - height) / 2, width, height };
}

bool loadBackground(const string &image)
{
	if (image.empty())
	{
		return true;
	}

	FREE_IMAGE_FORMAT format = FreeImage_GetFileType(image.c_str(), 0);
	if (format == FIF_UNKNOWN)
	{
		format = FreeImage_GetFIFFromFilename(image.c_str());
	}
	FIBITMAP *bitmap = format == FIF_UNKNOWN ? nullptr : FreeImage_Load(format, image.c_str());
	if (bitmap == nullptr)
	{
		cerr << "Unable to load background image: " << image << endl;
		return false;
	}

	int width = static_cast<int>(FreeImage_GetWidth(bitmap));
	int height = static_cast<int>(FreeImage_GetHeight(bitmap));
	int pitch = static_cast<int>(FreeImage_GetPitch(bitmap));
	vector<unsigned char> pixels(static_cast<size_t>(pitch) * height);
	bool converted = FreeImage_ConvertToRawBits(pixels.data(), bitmap, pitch, 24, 0, 0, 0, TRUE) != FALSE;
	FreeImage_Unload(bitmap);
	if (!converted)
	{
		cerr << "Unable to convert background image: " << image << endl;
		return false;
	}

	mBackground = SDL_CreateTexture(mRenderer, SDL_PIXELFORMAT_BGR24, SDL_TEXTUREACCESS_STATIC, width, height);
	if (mBackground == nullptr ||
	    !SDL_UpdateTexture(mBackground, nullptr, pixels.data(), width * BYTES))
	{
		cerr << "Unable to create background texture: " << SDL_GetError() << endl;
		return false;
	}
	return true;
}

bool loadOverlay(const SDL_FRect &rect)
{
	int width = static_cast<int>(rect.w);
	int height = static_cast<int>(rect.h);
	int pixelSize = max(1, static_cast<int>(overlay_scale * rect.w / source_width));
	vector<unsigned char> pixels(static_cast<size_t>(width) * height * 4, 0);

	for (int y = 0; y < height; y++)
	{
		for (int x = 0; x < width; x++)
		{
			pixels[(static_cast<size_t>(y) * width + x) * 4 + 3] =
				(x % pixelSize == 0 || y % pixelSize == 0) ? 64 : 0;
		}
	}

	mOverlay = SDL_CreateTexture(mRenderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STATIC, width, height);
	if (mOverlay == nullptr ||
	    !SDL_SetTextureBlendMode(mOverlay, SDL_BLENDMODE_BLEND) ||
	    !SDL_UpdateTexture(mOverlay, nullptr, pixels.data(), width * 4))
	{
		cerr << "Unable to create overlay texture: " << SDL_GetError() << endl;
		return false;
	}
	return true;
}

bool init(Uint8 red, Uint8 green, Uint8 blue)
{
	if (source_width <= 0 || source_height <= 0)
	{
		cerr << "Neither width nor height parameters can be 0." << endl;
		return false;
	}
	if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK))
	{
		cerr << "Unable to initialize SDL: " << SDL_GetError() << endl;
		return false;
	}

	const SDL_DisplayMode *displayMode = SDL_GetDesktopDisplayMode(SDL_GetPrimaryDisplay());
	if (displayMode == nullptr)
	{
		cerr << "Unable to get display dimensions: " << SDL_GetError() << endl;
		return false;
	}
	display_width = displayMode->w;
	display_height = displayMode->h;

	if (!SDL_CreateWindowAndRenderer("FreeJ2ME", display_width, display_height,
	                                SDL_WINDOW_FULLSCREEN, &mWindow, &mRenderer))
	{
		cerr << "Unable to create SDL window and renderer: " << SDL_GetError() << endl;
		return false;
	}

	SDL_SetRenderDrawColor(mRenderer, red, green, blue, 255);
	if (!SDL_SetRenderLogicalPresentation(mRenderer, display_width, display_height,
	                                      SDL_LOGICAL_PRESENTATION_LETTERBOX))
	{
		cerr << "Unable to configure renderer: " << SDL_GetError() << endl;
		return false;
	}
	return true;
}

void drawFrame(const unsigned char *frame, int pitch, const SDL_FRect &dest)
{
	Uint64 now = SDL_GetTicks();
	if (now - last_time < 16)
	{
		return;
	}
	last_time = now;

	SDL_RenderClear(mRenderer);
	if (mBackground != nullptr)
	{
		SDL_RenderTexture(mRenderer, mBackground, nullptr, nullptr);
	}
	SDL_UpdateTexture(mTexture, nullptr, frame, pitch);
	SDL_RenderTextureRotated(mRenderer, mTexture, nullptr, &dest, angle, nullptr, SDL_FLIP_NONE);
	SDL_RenderTextureRotated(mRenderer, mOverlay, nullptr, &dest, angle, nullptr, SDL_FLIP_NONE);
	SDL_RenderPresent(mRenderer);
}

void readFrames()
{
	const size_t frameSize = static_cast<size_t>(source_width) * source_height * BYTES;
	vector<unsigned char> frame(frameSize);
	size_t received = 0;
	pollfd input{ STDIN_FILENO, POLLIN | POLLHUP, 0 };
	while (capturing)
	{
		input.revents = 0;
		int ready = poll(&input, 1, 50);
		if (ready < 0)
		{
			if (errno == EINTR)
			{
				continue;
			}
			cerr << "Unable to poll frame input: " << strerror(errno) << endl;
			break;
		}
		if (ready == 0)
		{
			continue;
		}

		ssize_t count = read(STDIN_FILENO, frame.data() + received, frameSize - received);
		if (count == 0)
		{
			break;
		}
		if (count < 0)
		{
			if (errno == EINTR || errno == EAGAIN)
			{
				continue;
			}
			cerr << "Unable to read frame input: " << strerror(errno) << endl;
			break;
		}

		received += static_cast<size_t>(count);
		if (received == frameSize)
		{
			{
				lock_guard<mutex> lock(frameMutex);
				latestFrame = frame;
				frameReady = true;
			}
			received = 0;
		}
	}

	frameInputDone = true;
}

void handleEvent(const SDL_Event &event)
{
	int key;
	switch (event.type)
	{
	case SDL_EVENT_QUIT:
	case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
		capturing = false;
		break;
	case SDL_EVENT_KEY_DOWN:
	case SDL_EVENT_KEY_UP:
		key = event.key.key;
		if (key == SDLK_F4)
		{
			key = -1;
			capturing = false;
		}
		sendKey(key, event.type == SDL_EVENT_KEY_DOWN, false);
		break;
	case SDL_EVENT_JOYSTICK_BUTTON_DOWN:
	case SDL_EVENT_JOYSTICK_BUTTON_UP:
		sendKey(event.jbutton.button, event.type == SDL_EVENT_JOYSTICK_BUTTON_DOWN, true);
		break;
	case SDL_EVENT_JOYSTICK_HAT_MOTION:
		sendKey(event.jhat.value << 16, event.jhat.value != SDL_HAT_CENTERED, true);
		break;
	case SDL_EVENT_JOYSTICK_AXIS_MOTION:
	{
		auto axisIt = mPrevAxisValues.find(event.jaxis.which);
		if (axisIt == mPrevAxisValues.end() || event.jaxis.axis >= axisIt->second.size())
		{
			break;
		}
		int value = abs(event.jaxis.value) <= DEADZONE ? 0 : (event.jaxis.value > 0 ? 1 : -1);
		int &previous = axisIt->second[event.jaxis.axis];
		if (value != previous)
		{
			key = 3 * event.jaxis.axis + value + 1;
			sendKey(key << 8, value != 0, true);
			previous = value;
		}
		break;
	}
	case SDL_EVENT_JOYSTICK_ADDED:
		addJoystick(event.jdevice.which);
		break;
	case SDL_EVENT_JOYSTICK_REMOVED:
		removeJoystick(event.jdevice.which);
		break;
	default:
		break;
	}
	fflush(stdout);
}

int main(int argc, char *argv[])
{
	if (argc >= 2 && (string("--help") == argv[1] || string("-h") == argv[1]))
	{
		cout << getHelp() << endl;
		return 0;
	}
	if (argc < 3)
	{
		cout << getHelp() << endl;
		return 1;
	}

	source_width = atoi(argv[1]);
	source_height = atoi(argv[2]);
	string background;
	Uint8 red = 44, green = 62, blue = 80;

	for (int i = 3; i < argc; i++)
	{
		string option = argv[i];
		if (option == "-r" && i + 1 < argc)
		{
			angle = atoi(argv[++i]) % 360;
		}
		else if (option == "-i" && i + 1 < argc)
		{
			interpol = argv[++i];
		}
		else if (option == "-b" && i + 1 < argc)
		{
			background = argv[++i];
		}
		else if (option == "-s" && i + 1 < argc)
		{
			additional_scale = atof(argv[++i]);
		}
		else if (option == "-c" && i + 3 < argc)
		{
			red = static_cast<Uint8>(atoi(argv[++i]));
			green = static_cast<Uint8>(atoi(argv[++i]));
			blue = static_cast<Uint8>(atoi(argv[++i]));
		}
	}

	if (!init(red, green, blue))
	{
		SDL_Quit();
		return 1;
	}

	SDL_FRect dest = getDestinationRect();
	if (!loadBackground(background) || !loadOverlay(dest))
	{
		SDL_Quit();
		return 1;
	}
	SDL_ScaleMode scaleMode = SDL_SCALEMODE_NEAREST;
	if (interpol == "linear" || interpol == "best")
	{
		scaleMode = SDL_SCALEMODE_LINEAR;
	}
	if (mBackground != nullptr && !SDL_SetTextureScaleMode(mBackground, scaleMode))
	{
		cerr << "Unable to set background interpolation: " << SDL_GetError() << endl;
	}
	mTexture = SDL_CreateTexture(mRenderer, SDL_PIXELFORMAT_RGB24, SDL_TEXTUREACCESS_STREAMING,
	                             source_width, source_height);
	if (mTexture == nullptr)
	{
		cerr << "Unable to create frame texture: " << SDL_GetError() << endl;
		SDL_Quit();
		return 1;
	}
	if (!SDL_SetTextureScaleMode(mTexture, scaleMode))
	{
		cerr << "Unable to set frame interpolation: " << SDL_GetError() << endl;
	}

	int joystickCount = 0;
	SDL_JoystickID *joystickIds = SDL_GetJoysticks(&joystickCount);
	for (int i = 0; joystickIds != nullptr && i < joystickCount; i++)
	{
		addJoystick(joystickIds[i]);
	}
	SDL_free(joystickIds);
	SDL_SetJoystickEventsEnabled(true);

	bool initialCursorState = SDL_CursorVisible();
	SDL_HideCursor();
	thread frameThread(readFrames);
	while (capturing)
	{
		SDL_Event event;
		if (SDL_WaitEventTimeout(&event, 16))
		{
			handleEvent(event);
			while (SDL_PollEvent(&event))
			{
				handleEvent(event);
			}
		}

		vector<unsigned char> frame;
		{
			lock_guard<mutex> lock(frameMutex);
			if (frameReady)
			{
				frame.swap(latestFrame);
				frameReady = false;
			}
		}
		if (!frame.empty())
		{
			drawFrame(frame.data(), source_width * BYTES, dest);
		}
		if (frameInputDone)
		{
			capturing = false;
		}
	}
	frameThread.join();

	if (initialCursorState)
	{
		SDL_ShowCursor();
	}
	if (mTexture != nullptr) SDL_DestroyTexture(mTexture);
	if (mOverlay != nullptr) SDL_DestroyTexture(mOverlay);
	if (mBackground != nullptr) SDL_DestroyTexture(mBackground);
	for (auto &joystick : mJoysticks) SDL_CloseJoystick(joystick.second);
	if (mRenderer != nullptr) SDL_DestroyRenderer(mRenderer);
	if (mWindow != nullptr) SDL_DestroyWindow(mWindow);
	SDL_Quit();
	return 0;
}
