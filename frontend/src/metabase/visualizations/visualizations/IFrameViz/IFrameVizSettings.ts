import { t } from "ttag";

import {
  getDefaultSize,
  getMinSize,
} from "metabase/visualizations/shared/utils/sizes";

export const settings = {
  getUiName: () => "iframe",
  canSavePng: false,
  identifier: "iframe",
  iconName: "link",
  disableSettingsConfig: true,
  noHeader: true,
  supportsSeries: false,
  hidden: true,
  supportPreviewing: true,
  minSize: getMinSize("iframe"),
  defaultSize: getDefaultSize("iframe"),
  checkRenderable: () => {},
  settings: {
    "card.title": {
      dashboard: false,
      get default() {
        return t`Iframe card`;
      },
    },
    "card.description": {
      dashboard: false,
    },
    iframe: {
      value: "",
      default: "",
    },
  },
};
