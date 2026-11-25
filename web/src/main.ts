import './styles/tokens.css';
import './styles/base.css';
import './styles/primitives.css';
import './styles/prose.css';

import { mount } from 'svelte';
import App from './App.svelte';

const target = document.getElementById('app');
if (!target) {
  throw new Error('Missing #app root container');
}

mount(App, { target });
