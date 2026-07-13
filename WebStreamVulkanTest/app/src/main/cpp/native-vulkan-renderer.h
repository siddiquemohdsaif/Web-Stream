#pragma once
#include <android/asset_manager.h>
#include <android/native_window.h>
#include <cstdint>
#include <string>

std::string vulkanSetWindow(ANativeWindow* window, AAssetManager* assets);
void vulkanDestroy();
void vulkanSubmitYuv420(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                        int width, int height, uint16_t rotation, bool mirror);
