import React, { useState, useRef, useEffect } from 'react';
import {
  Box,
  Fab,
  Paper,
  Typography,
  TextField,
  IconButton,
  Stack,
  Chip,
  Collapse,
  Tooltip,
  CircularProgress,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import HelpOutlineIcon from '@mui/icons-material/HelpOutline';
import SendIcon from '@mui/icons-material/Send';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import api from '../../utils/api';
import { useAuth } from '../../context/AuthContext';

const formatReply = (text) =>
  (text || '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .trim();

/**
 * ShareBite Help — FAQ + keyword retrieval (server). Uses JWT role when logged in;
 * guests on the home page use the public endpoint.
 */
const ShareBiteHelpChat = () => {
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [messages, setMessages] = useState(() => [
    {
      role: 'assistant',
      text: user
        ? `Hi ${user.username}. Ask about donations, AI freshness, your ${user.userType} panel, or composting.`
        : 'Hi! Ask how ShareBite works, how to register, or what each role does.',
    },
  ]);
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, open]);

  const sendMessage = async (forcedText) => {
    const q = (forcedText != null ? String(forcedText) : input).trim();
    if (!q || loading) return;
    setInput('');
    setMessages((m) => [...m, { role: 'user', text: q }]);
    setLoading(true);
    try {
      const path = user ? '/chatbot/message' : '/public/chatbot/message';
      const { data } = await api.post(path, { message: q });
      const reply = formatReply(data.reply);
      const mode = data.sourceMode || 'faq';
      setMessages((m) => [
        ...m,
        {
          role: 'assistant',
          text: reply,
          meta: mode,
          matches: data.matches || [],
        },
      ]);
    } catch (err) {
      const msg =
        err.response?.data?.message ||
        err.message ||
        'Could not reach the help service. Is Spring Boot running?';
      setMessages((m) => [...m, { role: 'assistant', text: msg, error: true }]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <Tooltip title="ShareBite Help">
        <Fab
          color="primary"
          size="medium"
          aria-label="help chat"
          onClick={() => setOpen((v) => !v)}
          sx={{
            position: 'fixed',
            right: 24,
            bottom: 24,
            zIndex: (theme) => theme.zIndex.drawer + 2,
          }}
        >
          {open ? <CloseIcon /> : <HelpOutlineIcon />}
        </Fab>
      </Tooltip>
      <Collapse in={open} orientation="vertical" sx={{ position: 'fixed', right: 24, bottom: 88, zIndex: (t) => t.zIndex.drawer + 2 }}>
        <Paper
          elevation={8}
          sx={{
            width: { xs: '100vw', sm: 380 },
            maxWidth: 'calc(100vw - 32px)',
            height: 440,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
            borderRadius: 2,
          }}
        >
          <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 2, py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
            <SmartToyIcon color="primary" fontSize="small" />
            <Typography variant="subtitle1" fontWeight={700} flex={1}>
              ShareBite Help
            </Typography>
            <IconButton size="small" onClick={() => setOpen(false)} aria-label="close">
              <CloseIcon fontSize="small" />
            </IconButton>
          </Stack>
          <Box sx={{ flex: 1, overflowY: 'auto', p: 1.5, bgcolor: (t) => (t.palette.mode === 'dark' ? 'grey.900' : 'grey.50') }}>
            {messages.map((msg, i) => (
              <Box
                key={i}
                sx={{
                  mb: 1.5,
                  display: 'flex',
                  justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
                }}
              >
                <Paper
                  elevation={0}
                  sx={{
                    maxWidth: '92%',
                    px: 1.5,
                    py: 1,
                    borderRadius: 2,
                    bgcolor: msg.role === 'user' ? 'primary.main' : 'background.paper',
                    color: msg.role === 'user' ? 'primary.contrastText' : 'text.primary',
                    border: msg.error ? 1 : 0,
                    borderColor: 'error.main',
                  }}
                >
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                    {msg.text}
                  </Typography>
                  {msg.matches?.length > 0 && (
                    <Stack direction="row" flexWrap="wrap" gap={0.5} sx={{ mt: 1 }}>
                      {msg.matches.map((m) => (
                        <Chip key={m.id} size="small" label={m.title} variant="outlined" />
                      ))}
                    </Stack>
                  )}
                  {msg.meta && (
                    <Typography variant="caption" sx={{ opacity: 0.7, display: 'block', mt: 0.5 }}>
                      Source: {msg.meta}
                    </Typography>
                  )}
                </Paper>
              </Box>
            ))}
            {loading && (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 1 }}>
                <CircularProgress size={28} />
              </Box>
            )}
            <div ref={bottomRef} />
          </Box>
          <Box sx={{ p: 1, borderTop: 1, borderColor: 'divider' }}>
            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
              {(user
                ? ['How do I publish a donation?', 'What does AI freshness do?']
                : ['How do I register?', 'What roles exist?']
              ).map((s) => (
                <Chip
                  key={s}
                  size="small"
                  label={s}
                  onClick={() => sendMessage(s)}
                  sx={{ cursor: 'pointer' }}
                />
              ))}
            </Stack>
            <Stack direction="row" spacing={1} alignItems="flex-end">
              <TextField
                fullWidth
                size="small"
                placeholder="Type your question…"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && (e.preventDefault(), sendMessage())}
                multiline
                maxRows={3}
              />
              <IconButton color="primary" onClick={() => sendMessage()} disabled={loading || !input.trim()} aria-label="send">
                <SendIcon />
              </IconButton>
            </Stack>
          </Box>
        </Paper>
      </Collapse>
    </>
  );
};

export default ShareBiteHelpChat;
