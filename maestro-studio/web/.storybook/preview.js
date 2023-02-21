import { withRouter } from 'storybook-addon-react-router-v6';

import '../src/index.css'
import {installMocks} from "../src/mocks";

installMocks()

export const decorators = [withRouter];

export const parameters = {
  actions: { argTypesRegex: "^on[A-Z].*" },
  controls: {
    matchers: {
      color: /(background|color)$/i,
      date: /Date$/,
    },
  },
}