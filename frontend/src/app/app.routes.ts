import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '/dlq',
    pathMatch: 'full'
  },
  {
    path: 'dlq',
    loadComponent: () => import('./components/dlq/dlq.component').then(m => m.DlqComponent)
  },
  {
    path: 'pubsub',
    loadComponent: () => import('./components/pubsub/pubsub.component').then(m => m.PubsubComponent)
  },
  {
    path: 'request-reply',
    loadComponent: () => import('./components/request-reply/request-reply.component').then(m => m.RequestReplyComponent)
  },
  {
    path: 'work-queue',
    loadComponent: () => import('./components/work-queue/work-queue.component').then(m => m.WorkQueueComponent)
  },
  {
    path: 'fan-out',
    loadComponent: () => import('./components/fan-out/fan-out.component').then(m => m.FanOutComponent)
  },
  {
    path: 'topic-routing',
    loadComponent: () => import('./components/topic-routing/topic-routing.component').then(m => m.TopicRoutingComponent)
  },
  {
    path: 'scheduled-messages',
    loadComponent: () => import('./components/scheduled-messages/scheduled-messages.component').then(m => m.ScheduledMessagesComponent)
  },
  {
    path: '**',
    redirectTo: '/dlq'
  }
];
