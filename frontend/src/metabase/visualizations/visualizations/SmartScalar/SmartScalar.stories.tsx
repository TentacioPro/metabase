import type { StoryFn } from "@storybook/react";

import {
  SdkVisualizationWrapper,
  VisualizationWrapper,
} from "__support__/storybook";
import type { MetabaseTheme } from "metabase/embedding-sdk/theme";
import { registerVisualization } from "metabase/visualizations";
import Visualization from "metabase/visualizations/components/Visualization";

import { SmartScalar } from "./SmartScalar";
import { mockSeries } from "./tests/test-mocks";

export default {
  title: "viz/SmartScalar",
  component: SmartScalar,
};

// @ts-expect-error: SmartScalar is not written in TypeScript yet.
registerVisualization(SmartScalar);

const MOCK_ROWS = [
  ["2019-10-01T00:00:00", 100],
  ["2019-11-01T00:00:00", 120],
];

const MOCK_SERIES = mockSeries({
  rows: MOCK_ROWS,
  insights: [{ unit: "month", col: "Count" }],
});

export const Default: StoryFn = () => (
  <VisualizationWrapper>
    <Visualization rawSeries={MOCK_SERIES} width={500} />
  </VisualizationWrapper>
);

// Example of how themes can be applied in the SDK.
export const EmbeddingTheme: StoryFn = () => {
  const theme: MetabaseTheme = {
    colors: {
      positive: "#4834d4",
      negative: "#e84118",
    },
    components: {
      number: {
        value: { fontSize: "24px", lineHeight: "20px" },
      },
    },
  };

  return (
    <SdkVisualizationWrapper theme={theme}>
      <Visualization rawSeries={MOCK_SERIES} width={500} />
    </SdkVisualizationWrapper>
  );
};
