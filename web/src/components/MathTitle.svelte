<script lang="ts">
  interface Props {
    /** Server-sanitized MathML/HTML from `render_inline_math_html`; never pass untrusted text. */
    html?: string | null;
    /** Plain-text fallback, rendered safely as escaped text whenever `html` is absent. */
    text: string;
  }

  let { html = null, text }: Props = $props();
</script>

{#if html}
  {@html html}
{:else}
  {text}
{/if}
