<script setup>
defineProps({ analysis: { type: Object, required: true } })
</script>

<template>
  <article class="resume-analysis">
    <h3>评分 {{ analysis.overallScore }} / 100</h3>
    <p class="resume-analysis-meta">{{ new Date(analysis.analyzedAt).toLocaleString('zh-CN') }} · {{ analysis.rubricVersion }}</p>
    <dl class="resume-score-grid">
      <div><dt>项目经验</dt><dd>{{ analysis.projectScore }} / 40</dd></div>
      <div><dt>技能匹配</dt><dd>{{ analysis.skillMatchScore }} / 20</dd></div>
      <div><dt>内容</dt><dd>{{ analysis.contentScore }} / 15</dd></div>
      <div><dt>结构</dt><dd>{{ analysis.structureScore }} / 15</dd></div>
      <div><dt>表达</dt><dd>{{ analysis.expressionScore }} / 10</dd></div>
    </dl>
    <section v-if="analysis.strengths?.length"><h4>优点</h4>
      <article v-for="(item, index) in analysis.strengths" :key="index" class="resume-insight">
        <p>{{ item.text }}</p>
        <details v-if="item.evidence?.length"><summary>查看原文依据</summary>
          <blockquote v-for="evidence in item.evidence" :key="evidence.evidenceId"><small>{{ evidence.evidenceId }} · {{ evidence.sourceLocation }}</small><p>{{ evidence.quote }}</p></blockquote>
        </details>
      </article>
    </section>
    <section><h4>改进建议</h4>
      <article v-for="(item, index) in analysis.suggestions" :key="index" class="resume-insight">
        <strong>{{ item.category }} · {{ item.priority }}优先级<span v-if="item.type === 'SUPPLEMENT'"> · 补充建议</span></strong>
        <p>{{ item.issue }}</p><p>{{ item.recommendation }}</p>
        <details v-if="item.evidence?.length"><summary>查看原文依据</summary>
          <blockquote v-for="evidence in item.evidence" :key="evidence.evidenceId"><small>{{ evidence.evidenceId }} · {{ evidence.sourceLocation }}</small><p>{{ evidence.quote }}</p></blockquote>
        </details>
      </article>
    </section>
  </article>
</template>
