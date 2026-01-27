import { Routes } from '@angular/router';
import { HomeComponent } from './home/home.component';
import { LogSearchComponent } from './log-search/log-search.component';
import { CopyRulesComponent } from './copy-rules/copy-rules.component';
import { CopyRuleFormComponent } from './copy-rules/copy-rule-form.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'logs', component: LogSearchComponent },
  { path: 'copy-rules', component: CopyRulesComponent },
  { path: 'copy-rules/new', component: CopyRuleFormComponent },
  { path: 'copy-rules/:id/edit', component: CopyRuleFormComponent },
  { path: '**', redirectTo: '' }
];

