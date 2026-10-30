// Optional Vue 3 composable. Call this from your existing playback component.
import { onMounted, onUnmounted } from 'vue'

export function useNativePlaybackPause(pause: () => void) {
  const handlePause = () => pause()
  onMounted(() => window.addEventListener('native-playback-pause', handlePause))
  onUnmounted(() => window.removeEventListener('native-playback-pause', handlePause))
}

// For a Video.js instance:
// useNativePlaybackPause(() => player.value?.pause())
// For an HTMLVideoElement:
// useNativePlaybackPause(() => videoRef.value?.pause())
