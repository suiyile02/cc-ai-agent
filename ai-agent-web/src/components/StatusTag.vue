<script setup>
defineProps({
  type: { type: String, default: 'neutral' }, // success | info | warn | danger | neutral
  text: { type: String, required: true },
  dot: { type: Boolean, default: true },
})
</script>

<template>
  <span class="tag" :class="type">
    <span v-if="dot" class="dot" />
    {{ text }}
  </span>
</template>
