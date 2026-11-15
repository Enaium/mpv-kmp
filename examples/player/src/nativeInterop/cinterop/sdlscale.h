/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef MPV_KMP_SDLSCALE_H
#define MPV_KMP_SDLSCALE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Declared so Kotlin/Native can query Android's display density through the
 * SDL3 symbols already linked by sdl-kmp. */
extern float SDL_GetDisplayContentScale(unsigned int displayID);

#ifdef __cplusplus
}
#endif

#endif
