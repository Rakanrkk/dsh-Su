import { registerHooks } from 'node:module'

const target = new URL('./dsh-tools-stub.js', import.meta.url).href

registerHooks({
  resolve(specifier, context, next) {
    if (specifier === '@deepseek-ai/dsh-tools') return { url: target, shortCircuit: true }
    return next(specifier, context)
  },
})
